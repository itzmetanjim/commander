package org.tanjim.colonel
import com.github.h0tk3y.betterParse.combinators.*
import com.github.h0tk3y.betterParse.grammar.Grammar
import com.github.h0tk3y.betterParse.grammar.parseToEnd
import com.github.h0tk3y.betterParse.lexer.literalToken
import com.github.h0tk3y.betterParse.lexer.regexToken
import com.github.h0tk3y.betterParse.parser.Parser
import com.palantir.javapoet.*
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import javax.lang.model.element.Modifier

enum class ArgType {
    Word, String, GreedyString, BlockPos, Bool, Double, Float, Integer, Long, Entity
}

data class RequiredArg(val name: kotlin.String, val type: ArgType)

data class OptionalArg(val name: kotlin.String, val type: ArgType, val default: kotlin.String)

data class OptionalEnumArg(val name: kotlin.String, val values: List<kotlin.String>, val default: kotlin.String)

sealed class CommandToken {
    data class Literal(val text: kotlin.String) : CommandToken()

    data class Required(val arg: RequiredArg) : CommandToken()

    data class Optional(val arg: OptionalArg) : CommandToken()

    data class OptionalEnum(val arg: OptionalEnumArg) : CommandToken()
}

sealed class CommandAction {
    data class ExplicitCall(val function: kotlin.String, val args: List<kotlin.String>) : CommandAction()

    data class AutoCall(val function: kotlin.String) : CommandAction()
}

data class CommandDef(
    val permissionLevel: Int,
    val tokens: List<CommandToken>,
    val action: CommandAction
)

data class CommandFile(
    val commands: List<CommandDef>)

class ColonelGrammar : Grammar<CommandFile>() {
    @Suppress("unused")
    val blockComment by regexToken("/\\*[\\s\\S]*?\\*/", ignore = true)
    @Suppress("unused")
    val lineComment by regexToken("(?://|#)[^\\n]*", ignore = true) //both python and js comments will work!
    @Suppress("unused")
    val newlines by regexToken("\\n+", ignore = true)
    @Suppress("unused")
    val ws by regexToken("[ \\t]+", ignore = true)

    val lAngle by literalToken("<")
    val rAngle by literalToken(">")
    val lBracket by literalToken("[")
    val rBracket by literalToken("]")
    val colon by literalToken(":")
    val equals by literalToken("=")
    val comma by literalToken(",")
    val lParen by literalToken("(")
    val rParen by literalToken(")")
    val arrow by literalToken("->")
    val pipeArrow by literalToken("|>")
    val at by literalToken("@")
    val inKeyword by literalToken("in")
    val dot by literalToken(".")

    val quotedString by regexToken("\"[^\"]*\"")
    val integer by regexToken("\\d+")
    val identifier by regexToken("[A-Za-z_][A-Za-z0-9_]*")


    val bareWord: Parser<String> by identifier use { text }
    val quotedValue: Parser<String> by quotedString use { text.removeSurrounding("\"") }
    val wordValue: Parser<String> by bareWord or quotedValue

    val dottedIdentifier: Parser<String> by separatedTerms(bareWord, dot) use { joinToString(".") }

    val argType: Parser<ArgType> by identifier use {
        when (text) {
            "Word" -> ArgType.Word
            "String" -> ArgType.String
            "GreedyString" -> ArgType.GreedyString
            "BlockPos" -> ArgType.BlockPos
            "Bool" -> ArgType.Bool
            "Double" -> ArgType.Double
            "Float" -> ArgType.Float
            "Integer" -> ArgType.Integer
            "Long" -> ArgType.Long
            "Entity" -> ArgType.Entity
            else -> throw IllegalArgumentException("Unknown argument type: $text")
        }
    }

    val requiredArg: Parser<CommandToken.Required> by
    -lAngle * bareWord * -colon * argType * -rAngle map { (name, type) ->
        CommandToken.Required(RequiredArg(name, type))
    }

    val enumValues: Parser<List<String>> by
    -lBracket * separatedTerms(wordValue, comma) * -rBracket

    val optionalEnumArg: Parser<CommandToken.OptionalEnum> by
    -lBracket * bareWord * -inKeyword * enumValues * optional(-equals * wordValue) * -rBracket map { (name, values, default) ->
        CommandToken.OptionalEnum(OptionalEnumArg(name, values, default ?: ""))
    }

    val optionalTypedArg: Parser<CommandToken.Optional> by
    -lBracket * bareWord * -colon * argType * -equals * wordValue * -rBracket map { (name, type, default) ->
        CommandToken.Optional(OptionalArg(name, type, default))
    }

    val literalToken: Parser<CommandToken.Literal> by wordValue map { CommandToken.Literal(it) }

    val commandToken: Parser<CommandToken> by requiredArg or optionalEnumArg or optionalTypedArg or literalToken

    val callArgs: Parser<List<String>> by
    -lParen * optional(separatedTerms(wordValue, comma)) * -rParen map { it ?: emptyList() }

    val explicitAction: Parser<CommandAction.ExplicitCall> by
    -arrow * dottedIdentifier * callArgs map { (func, args) ->
        CommandAction.ExplicitCall(func, args)
    }

    val autoAction: Parser<CommandAction.AutoCall> by
    -pipeArrow * dottedIdentifier map { CommandAction.AutoCall(it) }

    val permissionLevel: Parser<Int> by
    -at * integer use { text.toInt() }

    val commandDef: Parser<CommandDef> by
    optional(permissionLevel) * oneOrMore(commandToken) * (explicitAction or autoAction) map { (perm, tokens, action) ->
        CommandDef(perm ?: 0, tokens, action)
    }
    override val rootParser: Parser<CommandFile> by
    zeroOrMore(commandDef) map { CommandFile(it) }
}


enum class CommandSide { SERVER, CLIENT }



object FlatCodeGenerator {
    private val TEXT_CLASS = ClassName.get("net.minecraft.text", "Text")
    fun generate(
        commandFile: CommandFile,
        packageName: String,
        className: String,
        side: CommandSide
    ): JavaFile {
        val typeBuilder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
        if (side == CommandSide.CLIENT) {
            addClientBlockPosHelper(typeBuilder)
        }
        val registerMethod = generateRegisterMethod(commandFile.commands, side)
        typeBuilder.addMethod(registerMethod)
        return JavaFile.builder(packageName, typeBuilder.build())
            .indent("    ")
            .build()
    }
    private val CLIENT_CMD_SRC = ClassName.get("net.fabricmc.fabric.api.client.command.v2", "FabricClientCommandSource")
    private val CLIENT_CMD_MGR = ClassName.get("net.fabricmc.fabric.api.client.command.v2", "ClientCommandManager")
    private val SERVER_CMD_SRC = ClassName.get("net.minecraft.server.command", "ServerCommandSource")
    private val SERVER_CMD_MGR = ClassName.get("net.minecraft.server.command", "CommandManager")
    private val CLIENT_REG_CALLBACK = ClassName.get("net.fabricmc.fabric.api.client.command.v2", "ClientCommandRegistrationCallback")
    private val SERVER_REG_CALLBACK = ClassName.get("net.fabricmc.fabric.api.command.v2", "CommandRegistrationCallback")
    private val POS_ARGUMENT = ClassName.get("net.minecraft.command.argument", "PosArgument")
    private val BLOCK_POS = ClassName.get("net.minecraft.util.math", "BlockPos")
    private val COMMAND_CONTEXT = ClassName.get("com.mojang.brigadier.context", "CommandContext")
    private fun addClientBlockPosHelper(typeBuilder: TypeSpec.Builder) {
        val method = MethodSpec.methodBuilder("getClientBlockPos")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(BLOCK_POS)
            .addParameter(
                ParameterizedTypeName.get(COMMAND_CONTEXT, CLIENT_CMD_SRC),
                "context"
            )
            .addParameter(ClassName.get("java.lang", "String"), "name")
            .addStatement(
                "\$T arg = context.getArgument(name, \$T.class)",
                POS_ARGUMENT, POS_ARGUMENT
            )
            .addStatement(
                "return arg.toAbsoluteBlockPos(context.getSource().getPlayer().getCommandSource())"
            )
            .build()
        typeBuilder.addMethod(method)
    }
    private fun generateRegisterMethod(commands: List<CommandDef>, side: CommandSide): MethodSpec {
        val method = MethodSpec.methodBuilder("register")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .returns(TypeName.VOID)
        val regCallback = if (side == CommandSide.CLIENT) CLIENT_REG_CALLBACK else SERVER_REG_CALLBACK

        val grouped = commands.groupBy { cmd ->
            val first = cmd.tokens.first()
            require(first is CommandToken.Literal) { "Command must start with a literal word" }
            first.text
        }
        val bodyCode = StringBuilder()
        for ((rootLiteral, cmds) in grouped) {
            bodyCode.append(buildRootCommand(rootLiteral, cmds, side))
        }
        if (side == CommandSide.CLIENT) {
            method.addCode(
                "\$T.EVENT.register((dispatcher, registryAccess) -> {\n",
                regCallback
            )
        } else {
            method.addCode(
                "\$T.EVENT.register((dispatcher, registryAccess, environment) -> {\n",
                regCallback
            )
        }
        method.addCode(bodyCode.toString())
        method.addCode("});\n")
        return method.build()
    }
    private fun buildRootCommand(rootLiteral: String, commands: List<CommandDef>, side: CommandSide): String {
        val cmdMgr = if (side == CommandSide.CLIENT) "ClientCommandManager" else "CommandManager"

        val tree = CommandTreeBuilder.build(commands.map { it.copy(tokens = it.tokens.drop(1)) })
        val rendered = CommandTreeBuilder.render(tree, cmdMgr, side, 2)
        return "    dispatcher.register(\n" +
                "        ${cmdMgr}.literal(\"$rootLiteral\")\n" +
                rendered +
                "    );\n"
    }
}
/**
 * Builds a proper command tree from a list of CommandDefs and renders it as
 * Brigadier builder chain code.
 */
object CommandTreeBuilder {
    data class Node(
        val token: CommandToken? = null,
        var action: CommandAction? = null,
        var permissionLevel: Int = 0,
        var visibleArgs: List<Pair<String, CommandToken>> = emptyList(),
        val children: MutableList<Node> = mutableListOf()
    )
    fun build(commands: List<CommandDef>): Node {
        val root = Node()
        for (cmd in commands) {
            insert(root, cmd.tokens, cmd.action, cmd.permissionLevel, emptyList())
        }
        return root
    }
    private fun insert(
        parent: Node,
        tokens: List<CommandToken>,
        action: CommandAction,
        permLevel: Int,
        argsSoFar: List<Pair<String, CommandToken>>
    ) {
        if (tokens.isEmpty()) {
            parent.action = action
            parent.permissionLevel = permLevel
            parent.visibleArgs = argsSoFar
            return
        }
        val first = tokens.first()
        val rest = tokens.drop(1)

        val newArgs = argsSoFar.toMutableList()
        when (first) {
            is CommandToken.Required -> newArgs.add(first.arg.name to first)
            is CommandToken.Optional -> newArgs.add(first.arg.name to first)
            is CommandToken.OptionalEnum -> newArgs.add(first.arg.name to first)
            is CommandToken.Literal -> {} // literals don't add args
        }

        val existing = parent.children.find { matchToken(it.token, first) }
        if (existing != null) {
            if (rest.isEmpty()) {
                existing.action = action
                existing.permissionLevel = permLevel
                existing.visibleArgs = newArgs
            } else {
                insert(existing, rest, action, permLevel, newArgs)
            }
        } else {
            val newNode = Node(token = first)
            parent.children.add(newNode)
            if (rest.isEmpty()) {
                newNode.action = action
                newNode.permissionLevel = permLevel
                newNode.visibleArgs = newArgs
            } else {
                insert(newNode, rest, action, permLevel, newArgs)
            }
        }
    }
    private fun matchToken(a: CommandToken?, b: CommandToken?): Boolean {
        if (a == null || b == null) return false
        return when {
            a is CommandToken.Literal && b is CommandToken.Literal -> a.text == b.text
            a is CommandToken.Required && b is CommandToken.Required ->
                a.arg.name == b.arg.name && a.arg.type == b.arg.type
            a is CommandToken.Optional && b is CommandToken.Optional ->
                a.arg.name == b.arg.name && a.arg.type == b.arg.type
            a is CommandToken.OptionalEnum && b is CommandToken.OptionalEnum ->
                a.arg.name == b.arg.name
            else -> false
        }
    }
    fun render(root: Node, cmdMgr: String, side: CommandSide, baseIndent: Int): String {
        val sb = StringBuilder()
        for (child in root.children) {
            renderNode(child, cmdMgr, side, baseIndent, sb)
        }
        return sb.toString()
    }
    private fun renderNode(node: Node, cmdMgr: String, side: CommandSide, indent: Int, sb: StringBuilder) {
        val pad = "    ".repeat(indent)
        when (val token = node.token) {
            is CommandToken.Literal -> {
                sb.append("${pad}.then(${cmdMgr}.literal(\"${token.text}\")\n")
                appendPermissionAndExecutes(node, side, indent + 1, sb)
                for (child in node.children) {
                    renderNode(child, cmdMgr, side, indent + 1, sb)
                }
                sb.append("${pad})\n")
            }
            is CommandToken.Required -> {
                val factory = argFactory(token.arg.type)
                sb.append("${pad}.then(${cmdMgr}.argument(\"${token.arg.name}\", $factory)\n")
                appendPermissionAndExecutes(node, side, indent + 1, sb)
                for (child in node.children) {
                    renderNode(child, cmdMgr, side, indent + 1, sb)
                }
                sb.append("${pad})\n")
            }
            is CommandToken.Optional -> {
                if (node.action != null) {
                    appendPermissionAndExecutes(node, side, indent, sb, optionalDefault = token.arg)
                }
                val factory = argFactory(token.arg.type)
                sb.append("${pad}.then(${cmdMgr}.argument(\"${token.arg.name}\", $factory)\n")
                if (node.action != null) {
                    appendPermissionAndExecutes(node, side, indent + 1, sb)
                }
                for (child in node.children) {
                    renderNode(child, cmdMgr, side, indent + 1, sb)
                }
                sb.append("${pad})\n")
            }
            is CommandToken.OptionalEnum -> {
                if (node.action != null) {
                    appendPermissionAndExecutes(node, side, indent, sb, optionalEnumDefault = token.arg)
                }
                for (value in token.arg.values) {
                    sb.append("${pad}.then(${cmdMgr}.literal(\"$value\")\n")
                    if (node.action != null) {
                        appendPermissionAndExecutes(node, side, indent + 1, sb, enumValue = value, enumArgName = token.arg.name)
                    }
                    for (child in node.children) {
                        renderNode(child, cmdMgr, side, indent + 1, sb)
                    }
                    sb.append("${pad})\n")
                }
            }
            null -> {} // root node
        }
    }
    private fun appendPermissionAndExecutes(
        node: Node,
        side: CommandSide,
        indent: Int,
        sb: StringBuilder,
        optionalDefault: OptionalArg? = null,
        optionalEnumDefault: OptionalEnumArg? = null,
        enumValue: String? = null,
        enumArgName: String? = null
    ) {
        val action = node.action ?: return
        val pad = "    ".repeat(indent)
        if (node.permissionLevel > 0) {
            sb.append("${pad}.requires(source -> source.hasPermissionLevel(${node.permissionLevel}))\n")
        }
        sb.append("${pad}.executes(context -> {\n")
        val call = buildCall(action, node.visibleArgs, side, optionalDefault, optionalEnumDefault, enumValue, enumArgName)
        sb.append("${pad}    context.getSource().sendFeedback(Text.literal($call));\n")
        sb.append("${pad}    return 1;\n")
        sb.append("${pad}})\n")
    }
    private fun buildCall(
        action: CommandAction,
        visibleArgs: List<Pair<String, CommandToken>>,
        side: CommandSide,
        optionalDefault: OptionalArg?,
        optionalEnumDefault: OptionalEnumArg?,
        enumValue: String?,
        enumArgName: String?
    ): String {
        return when (action) {
            is CommandAction.ExplicitCall -> {
                val resolvedArgs = action.args.map { argRef ->
                    resolveArg(argRef, visibleArgs, side, optionalDefault, optionalEnumDefault, enumValue, enumArgName)
                }
                "${action.function}(${resolvedArgs.joinToString(", ")})"
            }
            is CommandAction.AutoCall -> {
                val resolvedArgs = visibleArgs.map { (name, token) ->
                    resolveTokenValue(name, token, side, optionalDefault, optionalEnumDefault, enumValue, enumArgName)
                }
                "${action.function}(${resolvedArgs.joinToString(", ")})"
            }
        }
    }
    private fun resolveArg(
        argRef: String,
        visibleArgs: List<Pair<String, CommandToken>>,
        side: CommandSide,
        optionalDefault: OptionalArg?,
        optionalEnumDefault: OptionalEnumArg?,
        enumValue: String?,
        enumArgName: String?
    ): String {
        if (enumArgName != null && argRef == enumArgName) {
            return if (enumValue != null) "\"$enumValue\"" else "\"${optionalEnumDefault?.default ?: ""}\""
        }

        if (optionalDefault != null && argRef == optionalDefault.name) {
            return defaultValueLiteral(optionalDefault)
        }

        if (optionalEnumDefault != null && argRef == optionalEnumDefault.name) {
            return "\"${optionalEnumDefault.default}\""
        }

        val found = visibleArgs.find { it.first == argRef }
        if (found != null) {
            return resolveTokenValue(found.first, found.second, side, null, null, enumValue, enumArgName)
        }

        return "\"$argRef\""
    }
    private fun resolveTokenValue(
        name: String,
        token: CommandToken,
        side: CommandSide,
        optionalDefault: OptionalArg?,
        optionalEnumDefault: OptionalEnumArg?,
        enumValue: String?,
        enumArgName: String?
    ): String {
        return when (token) {
            is CommandToken.Required -> extractArg(name, token.arg.type, side)
            is CommandToken.Optional -> {
                if (optionalDefault != null && name == optionalDefault.name) {
                    defaultValueLiteral(optionalDefault)
                } else {
                    extractArg(name, token.arg.type, side)
                }
            }
            is CommandToken.OptionalEnum -> {
                if (enumValue != null && name == (enumArgName ?: token.arg.name)) {
                    "\"$enumValue\""
                } else {
                    "\"${optionalEnumDefault?.default ?: token.arg.default}\""
                }
            }
            is CommandToken.Literal -> "\"${token.text}\""
        }
    }
    private fun defaultValueLiteral(arg: OptionalArg): String {
        return when (arg.type) {
            ArgType.Word, ArgType.String, ArgType.GreedyString -> "\"${arg.default}\""
            ArgType.Bool -> arg.default
            ArgType.Double -> arg.default
            ArgType.Float -> "${arg.default}f"
            ArgType.Integer, ArgType.Long -> arg.default
            ArgType.BlockPos -> "null"
            ArgType.Entity -> "null"
        }
    }
    private fun extractArg(name: String, type: ArgType, side: CommandSide): String {
        return when (type) {
            ArgType.Word -> "StringArgumentType.getString(context, \"$name\")"
            ArgType.String -> "StringArgumentType.getString(context, \"$name\")"
            ArgType.GreedyString -> "StringArgumentType.getString(context, \"$name\")"
            ArgType.Bool -> "BoolArgumentType.getBool(context, \"$name\")"
            ArgType.Double -> "DoubleArgumentType.getDouble(context, \"$name\")"
            ArgType.Float -> "FloatArgumentType.getFloat(context, \"$name\")"
            ArgType.Integer -> "IntegerArgumentType.getInteger(context, \"$name\")"
            ArgType.Long -> "LongArgumentType.getLong(context, \"$name\")"
            ArgType.BlockPos -> {
                if (side == CommandSide.CLIENT) "getClientBlockPos(context, \"$name\")"
                else "BlockPosArgumentType.getBlockPos(context, \"$name\")"
            }
            ArgType.Entity -> "EntityArgumentType.getEntity(context, \"$name\")"
        }
    }
    private fun argFactory(type: ArgType): String {
        return when (type) {
            ArgType.Word -> "StringArgumentType.word()"
            ArgType.String -> "StringArgumentType.string()"
            ArgType.GreedyString -> "StringArgumentType.greedyString()"
            ArgType.Bool -> "BoolArgumentType.bool()"
            ArgType.Double -> "DoubleArgumentType.doubleArg()"
            ArgType.Float -> "FloatArgumentType.floatArg()"
            ArgType.Integer -> "IntegerArgumentType.integer()"
            ArgType.Long -> "LongArgumentType.longArg()"
            ArgType.BlockPos -> "BlockPosArgumentType.blockPos()"
            ArgType.Entity -> "EntityArgumentType.entity()"
        }
    }
}



interface ColonelExtension {
    val packageName: Property<String>

    val className: Property<String>

    val side: Property<String>

    val commandsFile: Property<String>
}

abstract class GenerateCommandsTask : DefaultTask() {
    @get:InputFile
    abstract val inputFile: Property<File>
    @get:Input
    abstract val packageName: Property<String>
    @get:Input
    abstract val generatedClassName: Property<String>
    @get:Input
    abstract val commandSide: Property<String>
    @get:OutputDirectory
    abstract val outputDirectory: Property<File>
    @TaskAction
    fun generate() {
        val cmdsFile = inputFile.get()
        if (!cmdsFile.exists()) {
            logger.warn("Colonel: Commands file not found: ${cmdsFile.absolutePath}")
            return
        }
        val source = cmdsFile.readText()
        val grammar = ColonelGrammar()
        val commandFile = grammar.parseToEnd(source)
        if (commandFile.commands.isEmpty()) {
            logger.warn("Colonel: No commands found in ${cmdsFile.absolutePath}")
            return
        }
        val commandSide = when (commandSide.get().lowercase()) {
            "client" -> CommandSide.CLIENT
            "server" -> CommandSide.SERVER
            else -> throw IllegalArgumentException(
                "Colonel: Invalid side '${this.commandSide.get()}'. Must be 'client' or 'server'."
            )
        }
        val javaFile = FlatCodeGenerator.generate(
            commandFile,
            packageName.get(),
            generatedClassName.get(),
            commandSide
        )
        val outDir = outputDirectory.get()
        javaFile.writeTo(outDir)
        logger.lifecycle(
            "Colonel: Generated ${packageName.get()}.${generatedClassName.get()} " +
                    "with ${commandFile.commands.size} command(s) [${commandSide.name.lowercase()}]"
        )
    }
}

class ColonelParser : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("colonel", ColonelExtension::class.java)

        extension.className.convention("ColonelCommands")
        extension.side.convention("client")
        extension.commandsFile.convention("src/main/commands/main.cmds")
        val outputDir = project.layout.buildDirectory.dir("generated/sources/colonel/java/main")
        val generateTask = project.tasks.register("generateCommands", GenerateCommandsTask::class.java)
        project.afterEvaluate {
            val genTask = project.tasks.getByName("generateCommands") as GenerateCommandsTask
            genTask.group = "colonel"
            genTask.description = "Generate Brigadier command registration code from .cmds files"
            genTask.inputFile.set(project.file(extension.commandsFile.get()))
            genTask.packageName.set(extension.packageName)
            genTask.generatedClassName.set(extension.className)
            genTask.commandSide.set(extension.side)
            genTask.outputDirectory.set(outputDir.get().asFile)
            val cmdsFile = project.file(extension.commandsFile.get())
            if (cmdsFile.exists()) {
                project.plugins.withId("java") {
                    val sourceSets = project.extensions.getByType(
                        org.gradle.api.tasks.SourceSetContainer::class.java
                    )
                    val mainSourceSet = sourceSets.getByName("main")
                    mainSourceSet.java.srcDir(outputDir)
                }

                project.plugins.withId("org.jetbrains.kotlin.jvm") {
                    val sourceSets = project.extensions.getByType(
                        org.gradle.api.tasks.SourceSetContainer::class.java
                    )
                    val mainSourceSet = sourceSets.getByName("main")
                    mainSourceSet.java.srcDir(outputDir)
                }

                project.tasks.forEach { task ->
                    if (task.name == "compileJava" || task.name == "compileKotlin") {
                        task.dependsOn(generateTask)
                    }
                }
            } else {
                project.logger.info("Colonel: No commands file found at ${cmdsFile.absolutePath}, skipping code generation.")
            }
        }
    }
}

