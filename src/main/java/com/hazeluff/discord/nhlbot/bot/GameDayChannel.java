package com.hazeluff.discord.nhlbot.bot;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazeluff.discord.nhlbot.nhl.Game;
import com.hazeluff.discord.nhlbot.nhl.GameEvent;
import com.hazeluff.discord.nhlbot.nhl.GameEventStrength;
import com.hazeluff.discord.nhlbot.nhl.GameStatus;
import com.hazeluff.discord.nhlbot.nhl.Player;
import com.hazeluff.discord.nhlbot.nhl.Team;
import com.hazeluff.discord.nhlbot.nhl.custommessages.CanucksCustomMessages;
import com.hazeluff.discord.nhlbot.utils.DateUtils;

import sx.blah.discord.handle.obj.ICategory;
import sx.blah.discord.handle.obj.IChannel;
import sx.blah.discord.handle.obj.IGuild;
import sx.blah.discord.handle.obj.IMessage;

public class GameDayChannel extends Thread {
	private static final Logger LOGGER = LoggerFactory.getLogger(GameDayChannel.class);

	// Number of retries to do when NHL API returns no events.
	static final int NHL_EVENTS_RETRIES = 5;

	// Polling time for when game is not close to starting
	static final long IDLE_POLL_RATE_MS = 60000l;
	// Polling time for when game is started/almost-started
	static final long ACTIVE_POLL_RATE_MS = 5000l;
	// Time before game to poll faster
	static final long CLOSE_TO_START_THRESHOLD_MS = 300000l;
	// Time after game is final to continue updates
	static final long POST_GAME_UPDATE_DURATION = 600000l;

	// <threshold,message>
	@SuppressWarnings("serial")
	private final Map<Long, String> gameReminders = new HashMap<Long, String>() {
		{
			put(3600000l, "60 minutes till puck drop.");
			put(1800000l, "30 minutes till puck drop.");
			put(600000l, "10 minutes till puck drop.");
		}
	};

	static final String GAME_DAY_CHANNEL_CATEGORY_NAME = "Game Day Channels";

	private final NHLBot nhlBot;
	private final Game game;
	private final IGuild guild;

	private IChannel channel;
	private final Team team;

	private List<GameEvent> events = new ArrayList<>();
	private int eventsRetries = 0;

	// Map<eventId, message>
	private Map<Integer, IMessage> eventMessages = new HashMap<>();

	private IMessage endOfGameMessage;

	private boolean started = false;

	GameDayChannel(NHLBot nhlBot, Game game, IGuild guild, Team team) {
		this.nhlBot = nhlBot;
		this.game = game;
		this.guild = guild;
		this.team = team;
	}

	public static GameDayChannel get(NHLBot nhlBot, Game game, IGuild guild, Team team) {
		// Team team = nhlBot.getPreferencesManager().getTeamByGuild(guild.getLongID());
		GameDayChannel gameDayChannel = new GameDayChannel(nhlBot, game, guild, team);
		gameDayChannel.createChannel();
		return gameDayChannel;
	}

	/**
	 * Gets a {@link GameDayChannel} object for any game. Required objects are taken
	 * from the current object.
	 * 
	 * @param game
	 *            game to get {@link GameDayChannel} for. The game should have teams
	 *            that include the current object's team.
	 */
	private GameDayChannel getGameDayChannel(Game game) {
		return new GameDayChannel(nhlBot, game, guild, team);
	}

	@Override
	public void start() {
		if (!started) {
			started = true;
			superStart();
		} else {
			LOGGER.warn("Thread already started.");
		}
	}

	void superStart() {
		super.start();
	}

	@Override
	public void run() {
		String channelName = getChannelName();
		setName(String.format("<%s>-%s", guild.getName(), channelName));
		LOGGER.info("Started thread for channel [{}] in guild [{}]", channelName, guild.getName());

		try {
			if (game.getStatus() != GameStatus.FINAL) {
				// Wait until close to start of game
				LOGGER.info("Idling until near game start.");
				sendReminders();

				// Game is close to starting. Poll at higher rate than previously
				LOGGER.info("Game is about to start. Polling more actively.");
				boolean alreadyStarted = waitForStart();

				// Game has started
				if (!alreadyStarted) {
					LOGGER.info("Game is about to start!");
					sendStartOfGameMessage();
				} else {
					LOGGER.info("Game has already started.");
				}

				// If the game is not final after the post game updates, then it will loop back
				// and continue to track
				// the
				// game as if it hasn't ended yet.
				while (game.getStatus() != GameStatus.FINAL && !isInterrupted()) {
					updateChannel();
					// Game is over
					sendEndOfGameMessage();
					updatePinnedMessage();

					// Keep checking if game is over.
					updateChannelPostGame();
				}
			} else {
				LOGGER.info("Game is already finished");
			}
		} catch (InterruptedException e) {
			LOGGER.warn("Thread Terminated.");
		}
	}

	void createChannel() {
		String channelName = getChannelName();
		Predicate<IChannel> channelMatcher = c -> c.getName().equalsIgnoreCase(channelName);
		if (!guild.getChannels().stream().anyMatch(channelMatcher)) {
			channel = nhlBot.getDiscordManager().createChannel(guild, channelName);
			if (channel != null) {
				nhlBot.getDiscordManager().changeTopic(channel, team.getCheer());
				ZoneId timeZone = team.getTimeZone();
				IMessage message = nhlBot.getDiscordManager().sendMessage(channel, getDetailsMessage(timeZone));
				nhlBot.getDiscordManager().pinMessage(channel, message);
			}
		} else {
			LOGGER.warn("Channel [" + channelName + "] already exists in [" + guild.getName() + "]");
			channel = guild.getChannels().stream().filter(channelMatcher).findAny().get();
		}

		if (channel != null) {
			ICategory category = getCategory(guild, GAME_DAY_CHANNEL_CATEGORY_NAME);
			if (category != null) {
				nhlBot.getDiscordManager().moveChannel(category, channel);
			}
		}
	}

	/**
	 * Gets the existing category in the guild. If the category does not already
	 * exist, it will be created.
	 * 
	 * @param guild
	 *            guild to create the category in
	 * @param categoryName
	 *            name of the category
	 * @return the {@link ICategory}
	 */
	ICategory getCategory(IGuild guild, String categoryName) {
		ICategory category = nhlBot.getDiscordManager().getCategory(guild, categoryName);
		if (category == null) {
			category = nhlBot.getDiscordManager().createCategory(guild, categoryName);
		}
		return category;
	}

	/**
	 * Stops the thread and deletes the channel from the Discord Guild.
	 */
	void remove() {
		interrupt();
		nhlBot.getDiscordManager().deleteChannel(channel);
	}

	/**
	 * Sends reminders of time till the game starts.
	 * 
	 * @throws InterruptedException
	 */
	void sendReminders() throws InterruptedException {
		boolean firstPass = true;
		boolean closeToStart;
		long timeTillGameMs = Long.MAX_VALUE;
		do {
			timeTillGameMs = DateUtils.diffMs(ZonedDateTime.now(), game.getDate());
			closeToStart = timeTillGameMs < CLOSE_TO_START_THRESHOLD_MS;
			if (!closeToStart) {
				// Check to see if message should be sent.
				long lowestThreshold = Long.MAX_VALUE;
				String message = null;
				Iterator<Entry<Long, String>> it = gameReminders.entrySet().iterator();
				while (it.hasNext()) {
					Entry<Long, String> entry = it.next();
					long threshold = entry.getKey();
					if (threshold > timeTillGameMs) {
						if (lowestThreshold > threshold) {
							lowestThreshold = threshold;
							message = entry.getValue();
						}
						it.remove();
					}
				}
				if (message != null && !firstPass) {
					nhlBot.getDiscordManager().sendMessage(channel, message);
				}
				lowestThreshold = Long.MAX_VALUE;
				message = null;
				firstPass = false;
				LOGGER.trace("Idling until near game start. Sleeping for [" + IDLE_POLL_RATE_MS + "]");
				if (isInterrupted()) {
					return;
				}
				Thread.sleep(IDLE_POLL_RATE_MS);
			}
		} while (!closeToStart);
	}

	/**
	 * Polls at higher polling rate before game starts. Returns whether or not the
	 * game has already started
	 * 
	 * @return true, if game is already started<br>
	 *         false, otherwise
	 * @throws InterruptedException
	 */
	boolean waitForStart() throws InterruptedException {
		boolean alreadyStarted = game.getStatus() != GameStatus.PREVIEW;
		boolean started = false;
		do {
			game.update();
			started = game.getStatus() != GameStatus.PREVIEW;
			if (!started && !isInterrupted()) {
				LOGGER.trace("Game almost started. Sleeping for [" + ACTIVE_POLL_RATE_MS + "]");
				Thread.sleep(ACTIVE_POLL_RATE_MS);
			}
		} while (!started && !isInterrupted());
		return alreadyStarted;
	}

	/**
	 * Updates the Channel with Messages of events until the game is finished.
	 * 
	 * @throws InterruptedException
	 */
	void updateChannel() throws InterruptedException {
		while (game.getStatus() != GameStatus.FINAL && !isInterrupted()) {
			updateMessages();

			if (game.getStatus() != GameStatus.FINAL && !isInterrupted()) {
				LOGGER.trace("Game in Progress. Sleeping for [" + ACTIVE_POLL_RATE_MS + "]");
				Thread.sleep(ACTIVE_POLL_RATE_MS);
			}
		}
	}

	/**
	 * Updates/Posts/Removes Messages from the Channels based on the state of the
	 * Game's GameEvents.
	 */
	void updateMessages() {
		List<GameEvent> retrievedEvents = game.getEvents();
		if (retrievedEvents.isEmpty()) {
			if (events.size() > 1) {
				LOGGER.warn("NHL api returned no events, but we have stored more than one event.");
				return;
			} else if (events.size() == 1) {
				LOGGER.warn("NHL api returned no events, but we have stored one event.");
				if (eventsRetries++ < NHL_EVENTS_RETRIES) {
					LOGGER.warn(String.format(
							"Could be a rescinded goal or NHL api issue. " + "Retrying %s time(s) out of %s",
							eventsRetries, NHL_EVENTS_RETRIES));
					return;
				}
			}
		}
		eventsRetries = 0;

		retrievedEvents.forEach(retrievedEvent -> {
			if (!retrievedEvent.getPlayers().isEmpty()
					&& !events.stream().anyMatch(event -> event.equals(retrievedEvent))) {
				if (events.removeIf(event -> event.getId() == retrievedEvent.getId())) {
					// Updated events
					LOGGER.debug("Updated event: [" + retrievedEvent + "]");
					updateEventMessage(retrievedEvent);
				} else {
					// New events
					LOGGER.debug("New event: [" + retrievedEvent + "]");
					sendEventMessage(retrievedEvent);
				}
				events.add(retrievedEvent);
			}
		});

		// Deleted events
		events.forEach(event -> {
			if (!retrievedEvents.contains(event)) {
				LOGGER.debug("Removed event: [" + event + "]");
				sendDeletedEventMessage(event);
			}
		});

		events = game.getEvents();
	}

	/**
	 * Sends a message with information of the specified event to game channels of
	 * the specified game.
	 * 
	 * @param game
	 *            game of which it's channels will have the messages sent to
	 * @param event
	 *            to create the message from
	 */
	void sendEventMessage(GameEvent event) {
		LOGGER.info("Sending message for event [" + event + "].");
		String strMessage = buildEventMessage(event);
		IMessage message = nhlBot.getDiscordManager().sendMessage(channel, strMessage);
		eventMessages.put(event.getId(), message);
	}

	/**
	 * Update previously sent messages of the specified event with the new
	 * information in the event.
	 * 
	 * @param game
	 *            game of which it's channels will have the messages sent to
	 * @param event
	 *            updated event to create the new message from
	 */
	void updateEventMessage(GameEvent event) {
		LOGGER.info("Updating message for event [" + event + "].");
		if (!eventMessages.containsKey(event.getId())) {
			LOGGER.warn("No message exists for the event: {}", event);
		} else {
			String message = buildEventMessage(event);
			IMessage updatedMessage = nhlBot.getDiscordManager().updateMessage(eventMessages.get(event.getId()),
					message);
			eventMessages.put(event.getId(), updatedMessage);
		}
	}

	/**
	 * Sends a message, to indicate specified event was revoked, to game channels of
	 * the specified game.
	 * 
	 * @param game
	 *            game of which it's channels will have the messages sent to
	 * @param event
	 *            the deleted event to create the message from
	 */
	void sendDeletedEventMessage(GameEvent event) {
		LOGGER.info("Sending 'deleted event' message for event [" + event + "].");
		nhlBot.getDiscordManager().sendMessage(channel,
				String.format("Goal by %s has been rescinded.", event.getPlayers().get(0).getFullName()));
	}

	/**
	 * Build a message to deliver based on the event.
	 * 
	 * @param event
	 *            event to build message from
	 * @return message to send
	 */
	String buildEventMessage(GameEvent event) {
		GameEventStrength strength = event.getStrength();
		List<Player> players = event.getPlayers();
		StringBuilder message = new StringBuilder();

		// Custom goal message
		String customMessage = CanucksCustomMessages.getMessage(event.getPlayers());
		if (event.getId() % 4 == 0 && customMessage != null) {
			message.append(customMessage).append("\n");
		}

		// Regular message
		if (strength == GameEventStrength.EVEN) {
			message.append(
					String.format("%s goal by **%s**!", event.getTeam().getLocation(), players.get(0).getFullName()));
		} else {
			message.append(String.format("%s %s goal by **%s**!", event.getTeam().getLocation(),
					strength.getValue().toLowerCase(), players.get(0).getFullName()));
		}
		if (players.size() > 1) {
			message.append(String.format(" Assists: %s", players.get(1).getFullName()));
		}
		if (players.size() > 2) {
			message.append(String.format(", %s", players.get(2).getFullName()));
		}
		return message.toString();
	}

	/**
	 * Update the Channel/Messages for a duration after the end of the game.
	 * 
	 * @throws InterruptedException
	 */
	void updateChannelPostGame() throws InterruptedException {
		int iterations = 0;
		while (iterations * IDLE_POLL_RATE_MS < POST_GAME_UPDATE_DURATION && game.getStatus() == GameStatus.FINAL
				&& !isInterrupted()) {
			iterations++;
			updateMessages();
			updateEndOfGameMessage();
			updatePinnedMessage();
			if (game.getStatus() == GameStatus.FINAL && !isInterrupted()) {
				Thread.sleep(IDLE_POLL_RATE_MS);
			}
		}
	}

	/**
	 * Sends the 'Start of game' message to the game channels of the specified game.
	 * 
	 * @param game
	 *            game of which it's channels will have the messages sent to
	 */
	void sendStartOfGameMessage() {
		LOGGER.info("Sending start message.");
		nhlBot.getDiscordManager().sendMessage(channel, "Game is about to start! " + team.getCheer());
	}

	/**
	 * Send a message to the channel, at the end of a game to summarize the game.
	 */
	void sendEndOfGameMessage() {
		LOGGER.info("Sending end of game message for game.");
		endOfGameMessage = nhlBot.getDiscordManager().sendMessage(channel, buildEndOfGameMessage());
	}

	/**
	 * Updates the end of game messages that are already sent.
	 */
	void updateEndOfGameMessage() {
		LOGGER.info("Updating end of game message for game.");
		if (endOfGameMessage != null) {
			String newEndOfGameMessage = buildEndOfGameMessage();
			endOfGameMessage = nhlBot.getDiscordManager().updateMessage(endOfGameMessage, newEndOfGameMessage);
		} else {
			LOGGER.warn("End of game message do not exist for the game.");
		}
	}

	/**
	 * Builds the message that is sent at the end of the game.
	 * 
	 * @param game
	 *            the game to build the message for
	 * @param team
	 *            team to specialize the message for
	 * @return end of game message
	 */
	String buildEndOfGameMessage() {
		String message = "Game has ended. Thanks for joining!\n" + "Final Score: " + getScoreMessage() + "\n"
				+ "Goals Scored:\n" + game.getGoalsMessage();
		Game nextGame = nhlBot.getGameScheduler().getNextGame(team);
		if (nextGame != null) {
			message += "\nThe next game is: " + getGameDayChannel(nextGame).getDetailsMessage(team.getTimeZone());
		}
		return message;
	}

	/**
	 * Update the pinned message of the channel to include details (score/goals) of
	 * the game.
	 */
	public void updatePinnedMessage() {
		LOGGER.info("Updating pinned message.");
		IMessage pinnedMessage = nhlBot.getDiscordManager().getPinnedMessages(channel).stream()
				.filter(message -> nhlBot.getDiscordManager().isAuthorOfMessage(message)).findFirst().orElse(null);
		if (pinnedMessage != null) {
			nhlBot.getDiscordManager().updateMessage(pinnedMessage, buildEndOfGameMessage());
		}

	}

	/**
	 * Gets the date in the format "YY-MM-DD"
	 * 
	 * @param zone
	 *            time zone to convert the time to
	 * @return the date in the format "YY-MM-DD"
	 */
	public String getShortDate(ZoneId zone) {
		return getShortDate(game, zone);
	}

	/**
	 * Gets the date in the format "YY-MM-DD"
	 * 
	 * @param game
	 *            game to get the date from
	 * @param zone
	 *            time zone to convert the time to
	 * @return the date in the format "YY-MM-DD"
	 */
	public static String getShortDate(Game game, ZoneId zone) {
		return game.getDate().withZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("yy-MM-dd"));
	}

	/**
	 * Gets the date in the format "EEEE dd MMM yyyy"
	 * 
	 * @param game
	 *            game to get the date for
	 * @param zone
	 *            time zone to convert the time to
	 * @return the date in the format "EEEE dd MMM yyyy"
	 */
	public static String getNiceDate(Game game, ZoneId zone) {
		return game.getDate().withZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("EEEE d/MMM/yyyy"));
	}

	/**
	 * Gets the date in the format "EEEE dd MMM yyyy"
	 * 
	 * @param zone
	 *            time zone to convert the time to
	 * @return the date in the format "EEEE dd MMM yyyy"
	 */
	public String getNiceDate(ZoneId zone) {
		return getNiceDate(game, zone);
	}

	/**
	 * Gets the time in the format "HH:mm aaa"
	 * 
	 * @param game
	 * 			  game to get the time from
	 * @param zone
	 *            time zone to convert the time to
	 * @return the time in the format "HH:mm aaa"
	 */
	public static String getTime(Game game, ZoneId zone) {
		return game.getDate().withZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("H:mm z"));
	}

	/**
	 * Gets the time in the format "HH:mm aaa"
	 * 
	 * @param zone
	 *            time zone to convert the time to
	 * @return the time in the format "HH:mm aaa"
	 */
	public String getTime(ZoneId zone) {
		return getTime(game, zone);
	}

	/**
	 * Gets the name that a channel in Discord related to this game would have.
	 * 
	 * @return channel name in format: "AAA_vs_BBB-yy-MM-DD". <br>
	 *         AAA is the 3 letter code of home team<br>
	 *         BBB is the 3 letter code of away team<br>
	 *         yy-MM-DD is a date format
	 */
	public String getChannelName() {
		return getChannelName(game);
	}

	/**
	 * Gets the name that a channel in Discord related to this game would have.
	 * 
	 * @param game
	 *            game to get channel name for
	 * @return channel name in format: "AAA-vs-BBB-yy-MM-DD". <br>
	 *         AAA is the 3 letter code of home team<br>
	 *         BBB is the 3 letter code of away team<br>
	 *         yy-MM-DD is a date format
	 */
	public static String getChannelName(Game game) {
		String channelName = String.format("%.3s-vs-%.3s-%s", game.getHomeTeam().getCode(),
				game.getAwayTeam().getCode(), getShortDate(game, ZoneId.of("America/New_York")));
		return channelName.toLowerCase();

	}

	/**
	 * Gets the message that NHLBot will respond with when queried about this game
	 * 
	 * @param game
	 * 		      the game to get the message for
	 * @param timeZone
	 *            the time zone to localize to
	 * 
	 * @return message in the format: "The next game is:\n<br>
	 *         **Home Team** vs **Away Team** at HH:mm aaa on EEEE dd MMM yyyy"
	 */
	public static String getDetailsMessage(Game game, ZoneId timeZone) {
		String message = String.format("**%s** vs **%s** at **%s** on **%s**", game.getHomeTeam().getFullName(),
				game.getAwayTeam().getFullName(), getTime(game, timeZone), getNiceDate(game, timeZone));
		return message;
	}

	/**
	 * Gets the message that NHLBot will respond with when queried about this game
	 * 
	 * @param timeZone
	 *            the time zone to localize to
	 * 
	 * @return message in the format: "The next game is:\n<br>
	 *         **Home Team** vs **Away Team** at HH:mm aaa on EEEE dd MMM yyyy"
	 */
	public String getDetailsMessage(ZoneId timeZone) {
		return getDetailsMessage(game, timeZone);
	}

	/**
	 * Gets the message that NHLBot will respond with when queried about the score
	 * of the game
	 * 
	 * @return message in the format : "Home Team **homeScore** - **awayScore** Away
	 *         Team"
	 */
	public String getScoreMessage() {
		return getScoreMessage(game);
	}
	
	/**
	 * Gets the message that NHLBot will respond with when queried about the score
	 * of the game
	 * 
	 * @param game
	 * 		The game to get the score message of
	 * @return message in the format : "Home Team **homeScore** - **awayScore** Away
	 *         Team"
	 */
	public static String getScoreMessage(Game game) {
		return String.format("%s **%s** - **%s** %s", game.getHomeTeam().getName(), game.getHomeScore(),
				game.getAwayScore(), game.getAwayTeam().getName());
		
	}

	/**
	 * Determines if the given channel is in the "Game Day Channel" category.
	 * 
	 * @param channel
	 * @return true, if part of the category; false, otherwise.
	 */
	public static boolean isInCategory(IChannel channel) {
		return channel.getCategory().getName().equalsIgnoreCase(GAME_DAY_CHANNEL_CATEGORY_NAME);
	}

	/**
	 * Determines if the given channel name is that of a possible game. Does not
	 * factor into account whether or not the game is real.
	 * 
	 * @param channelName
	 *            name of the channel
	 * @return true, if is of game channel format;<br>
	 *         false, otherwise.
	 */
	public static boolean isChannelNameFormat(String channelName) {
		String teamRegex = String.join("|", Arrays.asList(Team.values()).stream()
				.map(team -> team.getCode().toLowerCase()).collect(Collectors.toList()));
		teamRegex = String.format("(%s)", teamRegex);
		String regex = String.format("%1$s-vs-%1$s-[0-9]{2}-[0-9]{2}-[0-9]{2}", teamRegex);
		return channelName.matches(regex);
	}
}