import com.j256.simplemagic.ContentInfoUtil
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

    private val contentInfoUtil = ContentInfoUtil()

    override fun execute() {
        refreshIndexIfNeed(indexPathObj, project, module)
        val mapValues = DrawableDetector(module).detect().mapValues {
            it.value.map { file ->
                file to File(file).extractImageDimension(contentInfoUtil).multi()
            }
        }
        mapValues.filter {
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
    Subcommand("RemoveUnused", "移除未使用的资源") {

    private val isDryRun by option(ArgType.Boolean, "dryRun", "d")
    private val full by option(ArgType.Boolean, "full", "f", "不会使用lint 的结果，遍历文件夹搜索所有的图片")

    override fun execute() {
        refreshIndexIfNeed(indexPathObj, project, module)
        (if (full == true) {
            listOf(
                NavigationDetector(module),
                LayoutDetector(module),
                DrawableDetector(module),
                RawDetector(module),
                ColorDetector(module),
                DimenDetector(module)
            )
        } else {
            listOf(ReportDetector(module))
        }).forEach {
            it.detect()
            it.run(indexPathObj, isDryRun == true)
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
