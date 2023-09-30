import com.j256.simplemagic.ContentInfoUtil
import java.io.File

fun File.drawableName() = name.split(".").first()

fun File.extractImageDimension(
    contentInfoUtil: ContentInfoUtil
) = contentInfoUtil.findMatch(this).message.split(",")[1].split("x").map { dimension ->
    dimension.trim().toInt()
}

fun drawables(module: File): Map<String, List<Pair<File, Int>>> {
    val contentInfoUtil = ContentInfoUtil()
    val file = File(module, "src/main/res/")
    val drawables = file.list { _, name ->
        name.startsWith("drawable")
    }.orEmpty()
    return drawables.flatMap { subDrawable ->
        val subDrawables = File(file, subDrawable)
        subDrawables.listFiles().orEmpty().filter {
            it.extension == "png"
        }.map {
            it to it.extractImageDimension(contentInfoUtil).multi()
        }
    }.groupBy {
        it.first.drawableName()
    }
}