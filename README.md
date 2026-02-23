# Commander
A better alternative to Brigadier.
# How to use
(See [this example mod](https://github.com/itzmetanjim/colonel-test-mod) for an example, or follow this tutorial)

## Step 1: Add this to your build.gradle or build.gradle.kts
Look in your project root. If you have a `build.gradle.kts` (kotlin) file, paste this
at the end of the `plugins` block:
```kotlin
plugins {
    /* ... whatever other thing you have ... */
        id("org.tanjim.commander") version "1.0.0"
}
```
Then at the end of the file, add:
```kotlin
commander {
    packageName.set("org.tanjim.colonel_test_mod") //change to your mod's package
    className.set("CommanderCommands")//keep this as is unless it causes conflicts
    side.set("server")//if your mod is client side only, change this to "client"
    commandsFile.set("src/main/commands/main.cmds")//use whatever path
}
```

If you have a `build.gradle` (groovy) file:
```groovy
plugins {
    /* ... whatever    */
        id 'org.tanjim.commander' version '1.0.0'
}
```
at the end:
```groovy
commander{
    packageName = "org.tanjim.colonel_test_mod"//change to your mod's package
    className = "CommanderCommands"//keep this as is unless it causes conflicts
    side = "server"//if your mod is client side only, change this to "client"
    commandsFile = "src/main/commands/main.cmds"//use whatever path you want
}
```

In the `commander{` block,
- `packageName` is the package that the generated command class will be in. Change this to your mod's package.
- `className` is the name of the generated command class. Change if there are conflicts (you have your own class with the same name).
- `side` is the side that the command will be registered on. If your mod is strictly client side, change this to "client". Otherwise, leave it as "server".
- `commandsFile` is the path to the file that will contain your commands.

To add Maven Central, add this to `settings.gradle` or `settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        mavenCentral() //add this!
        gradlePluginPortal()
    }
}
```
## Put all your command logic in a class
To each function, the commands arguments will be passed in order if you use `|>` or in whatever order you choose if you use `->`,
and the context will be passed as the last argument. When defining your function, the last argument must be of type 
`CommandContext<ServerCommandSource>` (or `CommandContext<CommandSource>` if your mod is client side).\
Make sure the function is static (in kotlin, put it in an `object` or `companion object` and annotate with `@JvmStatic`, in java, make it a static method).
Example:
```kotlin
//this is kotlin, its similar for java
@Suppress("unused") //intelliJ may not track colonel
object CTMCommands {
    @JvmStatic
    fun greet(context: CommandContext<ServerCommandSource>): String {
        return "Hello, ${context.source.name}!"
    }
    @JvmStatic
    fun addNumbers(a: Int, b: Int, context: CommandContext<ServerCommandSource>) : String{
        return "$a+$b=${a + b}"
    }
    fun doMath(a: Int, operator: String, b: Int, context: CommandContext<ServerCommandSource>) : String{
        /*ommitted for brevity*/
    }
}
```
## Create the commands file
In the path you set in `commandsFile`, create a new file.

You can write your commands in this file. An example is provided. For full syntax docs, see [Syntax](#Syntax)
```
~import org.tanjim.colonel_test_mod.CTMCommands
//comments start with # or // 
//block comments are /*like this*/
greet |> CTMCommands.greet
addnum <a:Integer> <b:Integer> |> CTMCommands.addNumbers
domath <a:Integer> [operator in ["+", "-", "*", "/"]] <b:Integer> |> CTMCommands.doMath
```

## Call the register function
Import:
```java
import your.mod.packagename.CommanderCommands;
```
Then in your `onInitialize` method, call:
```java
public void onInitialize() {
    //... whatever other init code you have ...
    CommanderCommands.register();
}
```

# Syntax
Each line is a command (or a comment/import). Bare words are interpreted as literals, or you can "quote" literals.
```
git init -> ...
//This is just literals
```
To add required arguments, use `<angle brackets>` and specify the type with a `:`.
These are the valid types:
- `Word` is an alphanumeric string without spaces, the only symbols allowed are letters,numbers and `+_-.`. They can't be quoted.
- `String` is a normal string. To use spaces, you have to quote them `"like this"`. Within quotes, you can use backslashes to escape.
   Unquoted strings only allow letters, numbers and `+_-.`, and can't have spaces.
- `GreedyString` is a string which captures the rest of the command. It must be the last argument. It can have spaces and any characters.
- `Integer` / `Long`
- `Float` / `Double` (decimal numbers)
- `Bool`
- `BlockPos` is a block position, like `~ ~1 ~` or `100 64 -200`. They will be given to the function as brigadier `BlockPosArgumentType` objects.
- `Entity` is an entity selector, given as a brigadier `EntityArgumentType`

Example:
```
addnum <a:Integer> <b:Integer> |> ...
```

Use `[square brackets]` for optional arguments. You can also specify a default value with `=`, otherwise the default is null.

```
create [name:Word=world] |> ...
```

Use the following syntax for enums:
```
doSomething [option in ["a", "b", "c"]="d"] |> ... //optional
doAnotherThing <option in ["x", "y", "z"]> |> ... //required
```

After a command definition, use one of the two operators to link it to a function:
- `|>` (pipe) this passes the function arguments in the order they are defined. So if your command is `doSomething <a> <b> <c>` then the function will be called with `(a, b, c, context)`
- `->` (executes) This allows you to specify the order of arguments (context is always last). Example:
```
doSomething <a> <b> <c> -> MyClass.myFunction(b, c, a)
```
Then `MyFunction` will be called with `(b, c, a, context)`
