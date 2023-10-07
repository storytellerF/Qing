import kotlinx.cli.*
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*
import kotlin.system.exitProcess

enum class FileChangeMode {
    NEW, CHANGE, DELETE
}

@OptIn(ExperimentalCli::class)
class DetectLarge(private val indexPathObj: Path, private val project: File, private val module: File) :
    Subcommand("DetectLarge", "检测大尺寸的drawable") {
    override fun execute() {
        refreshIndexIfNeed(indexPathObj, project, module)
        val groupBy = drawables(module)
        groupBy.filter {
            it.value.any { (_, size) ->
                size > 4 * 1024 * 1024
            }
        }.forEach { (t, u) ->
            println(t)
            u.sortedBy {
                it.second
            }.forEach {
                println("\t${it.second.toFloat() / 1048576} bytes")
            }
        }
    }

}

@ExperimentalCli
class RemoveUnused(private val indexPathObj: Path, private val project: File, private val module: File) :
    Subcommand("RemoveUnused", "移除未使用的图片") {

    private val isDryRun by option(ArgType.Boolean, "dryRun", "d")
    private val full by option(ArgType.Boolean, "full", "f", "不会使用lint 的结果，遍历文件夹搜索所有的图片")

    override fun execute() {
        refreshIndexIfNeed(indexPathObj, project, module)
        if (full == true) {
            drawables(module).mapValues {
                it.value.map { (file, _) ->
                    file.absolutePath
                }.toSet()
            }
        } else {
            val reportRoot = File(module, "build/reports/")
            val reportXmlPath = reportRoot.list { _, name ->
                name.endsWith("xml")
            }?.firstOrNull()
            if (reportXmlPath != null) {
                unusedDrawableFlow(reportRoot, reportXmlPath)
            } else {
                println("找不到对应的xml 文件")
                null
            }
        }?.let {
            val (count, c, space) = deleteUnused(indexPathObj, it, isDryRun == true)
            println("total ${it.size} delete $count $c space ${space.toFloat() / 1048576} MB")
        }

    }

}

@OptIn(ExperimentalCli::class)
class CleanIndex(private val indexPathObj: Path, private val project: File, private val module: File) :
    Subcommand("CleanIndex", "清除现有索引，然后退出") {
    private val rebuild by option(ArgType.Boolean, "rebuild", "r", "删除索引后重新创建索引")

    @OptIn(ExperimentalPathApi::class)
    override fun execute() {
        indexPathObj.deleteRecursively()
        if (rebuild == true)
            refreshIndexIfNeed(indexPathObj, project, module)
    }

}

@OptIn(ExperimentalCli::class)
fun main(args: Array<String>) {
    println("Program arguments: ${args.joinToString()}")
    if (args.isEmpty()) return
    val moduleName = "app"
    val projectDir = args.first()
    val module = File(projectDir, moduleName)
    val project = File(projectDir)

    val indexPathObj = Paths.get(projectDir, ".index")

    val argParser = ArgParser("Qing")
    val detectLarge = DetectLarge(indexPathObj, project, module)
    val removeUnused = RemoveUnused(indexPathObj, project, module)
    val cleanIndex = CleanIndex(indexPathObj, project, module)
    argParser.subcommands(detectLarge, removeUnused, cleanIndex)
    argParser.parse(args.sliceArray(1..<args.size))
}

private fun refreshIndexIfNeed(indexPathObj: Path, project: File, module: File) {
    val shaFlag = Paths.get(indexPathObj.absolutePathString(), "qing")
    if (ensure(shaFlag)) exitProcess(1)
    val oldSha = shaFlag.readText()
    val currentSha = getCurrentSha(project)
    if (oldSha == currentSha) {
        println("index not change!")
        return
    }
    println("refresh index -> old $oldSha new $currentSha")

    val srcFolders = refreshFolders(oldSha, currentSha, project, module)
    refreshIndex(indexPathObj, srcFolders)
    shaFlag.writeText(currentSha)
}


/**
 * @return 返回创建文件是否失败
 */
private fun ensure(shaFlag: Path): Boolean {
    if (!shaFlag.exists()) {
        shaFlag.createParentDirectories()
        shaFlag.createFile()
    }
    return false
}

fun List<Int>.multi(): Int {
    return fold(1) { a, i ->
        a * i
    }
}
