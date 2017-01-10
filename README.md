# discord-nhlbot

## Discord/Demo

[Join the Discord server](https://discord.gg/VVHe6d3)
Ask questions or have a look at the bot that is added to the server.

## Add bot:

###[Development](https://discordapp.com/oauth2/authorize?client_id=257345572162371588&scope=bot&permissions=93200)
###[Production](https://discordapp.com/oauth2/authorize?client_id=257345858515894272&scope=bot&permissions=93200)

### Permissions for bot

| Permission           | Hex        | Dec   |
|----------------------|------------|-------|
| MANAGE_CHANNELS      | 0x00000010 | 16    |
| READ_MESSAGES        | 0x00000400 | 1024  |
| SEND_MESSAGES        | 0x00000800 | 2048  |
| MANAGE_MESSAGES      | 0x00002000 | 8192  |
| EMBED_LINKS          | 0x00004000 | 16384 |
| READ_MESSAGE_HISTORY | 0x00010000 | 65636 |
| Combined             | 0x00016C10 | 93200 |

## Game Day Channel
- [x] Automatically generate channels for individual games
- [x] Give warnings before the game starts (60/30/10 minutes before).
- [x] Display messages for when goals are scored.
- [x] Display summaries of games.
- [x] Special messages for when canucks score.
- [ ] Bot messages during gameplay to cheer on team.

## Commands
`@NHLBot [command]`
- `fuckmessier` Fuck Messier.
- `subscribe [team]` Subscribes your guild to the team specified by [team]. [team] is the 3 letter code representing the team you want to subscribe to. This must be done by a user with admin priveledges on the server/guild. 
- `nextgame` Replies with information about the next game.
- `about` Displays information about bot/author.
- `help` Display list of commands.

### In 'Game Day Channels' only
- `score` Displays score of that game.
- `goals` Displays goals of that game.