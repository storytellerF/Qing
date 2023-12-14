import java.io.File
import java.nio.file.Path

data class Navigation(val list: List<Fragment>)
data class Fragment(val id: String, val name: String, val layout: String, val arguments: List<Argument>) {
    val fragmentName = name.substring(name.lastIndexOf("."))
}

data class Argument(val name: String)

class NavigationDetector(module: File) : Detector(module) {
    override fun detectInternal() = xmlResources(module, { it.startsWith("navigation") }) {
        it.extension == "xml"
    }

    override fun runInternal(indexObj: Path, isDry: Boolean) {
        val (count, c, space) = deleteUnusedXmlField(indexObj, data, isDry, module) {
            val (id, fullName) = it.split("-")
            val name = fullName.substring(fullName.lastIndexOf(".") + 1)
            listOf(id, "${name}Args", name)
        }
        println("navigation total ${data.size} delete $count $c space ${space.toFloat() / 1048576} MB")
    }

}