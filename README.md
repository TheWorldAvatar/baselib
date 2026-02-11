
# TWA Base Library

This project was formally known as the JPS Base Lib.

## Requirements

### GitHub Maven repository credentials

To be able to fetch from, and deploy to, the TWA Maven repositories hosted on GitHub you need to set up some credentials.
The following needs to be added to your Maven `settings.xml` file, which should be located here `${user.home}/.m2/settings.xml`:

```xml
<server>
    <id>github</id>
    <username></username>
    <password>{A GitHub Token with only package "read" (and "write" if you need to deploy) permissions}</password>
</server>
```

## Usage

To be written
