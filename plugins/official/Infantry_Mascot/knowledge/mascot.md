# Mascot (Infantry_Mascot)

Avatar style that draws each agent as a small living character; the pose follows the session state.

Settings: Configuration > Plugins > "Mascot avatars", node `Infantry_Mascot.avatars`.

- Motion: Static (default), Focus only, Everywhere.
- Pose change ms.
- State map: `SESSION_STATE=Mood;...`, default `STARTING=Greet;BUSY=Working;WAITING_INPUT=Notify;IDLE=Idle;EXITED=Sleepy;ORPHANED=Confused;FAILED=Alert`.
- Idle fidget seconds.
- Role looks: `role=eyes/finish/cat`.

The avatar style itself is chosen in the avatar picker; Mascot must be installed and enabled first. If its renderer fails to load, the app falls back to Portrait.
