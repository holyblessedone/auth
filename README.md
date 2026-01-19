# Auth
A Minecraft server-side mod for player authorization.

When logging in, the mod saves the player's IP address, allowing them to log in to the server without re-entering their password if the IP address matches the last one. If the IP address doesn't match, the server will prompt the player to log in.

All player data is stored in the world folder.

## Commands
Adds server commands
```
/register [str]
/login [str]
/auth remove [nickname]
```
