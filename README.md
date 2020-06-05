This is a fork of Devoldefy
# Devoldefy
Translates MCP source code to Yarn

# Changes
Fix Devoldefy bugs.

Use config file as input instead of console input.

Fix cannot remap method override with generic parameter (by some reflection hack).

Support remapping client server marker annotation

# How to use it
remapforge.json is an example

## How to get classpath

Add these into build.gradle
```
task printClasspath {
	doLast {
		configurations.testCompile.each { println it }
	}
}
```
and run `gradlew printClasspath`

Another method:
Open minecraft in dev environment and then invoke this:
```
public static void printClassPath() {
    System.out.println(
        Arrays.stream(
            ((URLClassLoader) ClassLoader.getSystemClassLoader()).getURLs()
        ).map(
            url -> "\"" + url.getFile().substring(1).replace("%20", " ") + "\""
        ).collect(Collectors.joining(",\n"))
    );
}
```
