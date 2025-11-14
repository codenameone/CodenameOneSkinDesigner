# Codename One Skin Designer

The Skin Designer is a Codename One app that allows you to visually design a Codename One "skin" representing a device type from two images and a bit of device specific details. This application serves both as a demo for working with Codename One and as a real tool that can be used to create device skins.

This tool was mostly designed for use in the web via the JavaScript port of Codename One, but it can also work thru the desktop port.

## Command Line Usage

To convert a single Android Virtual Device (AVD) skin directory into a Codename One skin archive:

```
java AvdSkinToCodenameOneSkin.java <path-to-avd-skin> [output.skin]
```

The converter can also scan an entire GitHub repository for Android skin definitions and convert each one automatically:

```
java AvdSkinToCodenameOneSkin.java --github <repo-url> [--ref <git-ref>] [--output <directory>]
```

The `--ref` option allows you to specify the branch or tag to clone, and `--output` chooses the directory where the generated `.skin` archives will be stored (defaults to `./converted-skins`). The command requires the `git` client to be available on the system `PATH` when cloning repositories.
