# Install Infantry AI in BattalionOS

**BattalionOS** is the core and shell that Infantry AI runs on. Apps run inside the BattalionOS window, and you can pop
an app out into its own window. The **Armory** is the app manager: it lists apps, installs them, updates them, rolls
them back and removes them.

## Install BattalionOS

1. Download `BattalionOS_<version>_x64_en-US.msi`.
2. Run it. It installs for your user only. You do not need admin rights.
3. On the **Install apps** page, leave **Infantry AI** checked.

The first time BattalionOS opens, it installs Infantry AI from the app catalog in the background, on the `stable`
channel.

For a silent install without Infantry AI:

```
msiexec /i BattalionOS.msi /qn INSTALL_INFANTRY=0
```

## Add Infantry AI later

Open the **Armory**, find **Infantry AI** and install it. Apps come as `.bosapp` packages. The Armory checks each
package before it installs it.

Each installed app gets a Start menu shortcut in **BattalionOS Apps**. It opens BattalionOS straight into that app.

## Update or remove

Use the Armory. It can update an app, roll it back to the version before, or remove it.

## Next

[Create your first project](first-project.md).
