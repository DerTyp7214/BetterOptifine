import com.google.gson.Gson
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.*
import kotlin.collections.ArrayList

object Main {
    private const val versionManifest = "https://launchermeta.mojang.com/mc/game/version_manifest.json"

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val path = File(System.getProperty("user.dir"), "tmp")
            path.mkdirs()
            val arguments = args.map {
                Pair(it.split("=").first(), it.split("=").last())
            }.toMap()
            if (arguments["deleteThisFuckingDirectory"] == "true") {
                path.deleteRecursively()
            } else {
                val versionString = arguments["version"]
                if (versionString != null) {
                    val optifine = downloadOptifine(versionString, path)
                    val gson = Gson()
                    val manifest = gson.fromJson(URL(versionManifest).readText(), VersionManifest::class.java)
                    val version = manifest.versions.findLast {
                        it.id == arguments["version"]
                    }
                    if (version != null && optifine != null) {
                        val versionPath = File(path, "versions/${version.id}")
                        versionPath.mkdirs()
                        val versionJsonText = URL(version.url).readText()
                        val versionJson = gson.fromJson(versionJsonText, VersionJson::class.java)
                        Files.copy(
                            URL(versionJson.downloads.client.url).openStream(),
                            File(versionPath, "${version.id}.jar").toPath(),
                            StandardCopyOption.REPLACE_EXISTING
                        )
                        File(versionPath, "${version.id}.json").writeText(versionJsonText)
                        doExtract(optifine, path).walk().findLast {
                            it.isFile && it.name.endsWith(".jar")
                        }?.copyTo(File("OptiFile_${version.id}.jar"), true)
                        Runtime.getRuntime().exec(
                            arrayOf(
                                "java",
                                "-jar",
                                File(this::class.java.protectionDomain.codeSource.location.path).name,
                                "deleteThisFuckingDirectory=true"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun downloadOptifine(version: String, path: File): File? {
        val versions =
            Regex("(<a href=\"(http:\\/\\/optifine\\.net\\/adloadx\\?f=OptiFine_(.*)\\.jar)\">\\(Mirror\\)<\\/a>)").findAll(
                URL("https://optifine.net/downloads").readText()
            )
        val optifineVersion = versions.findLast {
            val (_, _, name) = it.destructured
            name.startsWith("${version}_")
        }
        return if (optifineVersion != null) {
            val url = optifineVersion.groupValues[2].replaceFirst("http", "https")
            val result = Regex("<a href=[\"'](downloadx\\?f=OptiFine_.*\\.jar&x=.{32})[\"']").find(URL(url).readText())
            if (result != null) {
                val downloadUrl = url.replace(Regex("adloadx.*"), result.groupValues[1])
                val output = File(path, "OptiFile.jar")
                Files.copy(URL(downloadUrl).openStream(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
                output
            } else null
        } else null
    }

    private fun doExtract(optifineJar: File, dirMc: File): File {
        val child = URLClassLoader(arrayOf(optifineJar.toURI().toURL()), javaClass.classLoader)
        val installer = Class.forName("optifine.Installer", true, child)
        val dirMcLib = File(dirMc, "libraries")
        val ofVer = getMethod<String>(installer, "getOptiFineVersion")
        val ofVers = tokenize(ofVer, "_")
        val mcVer = ofVers[1]
        val ofEd = getMethod<String>(installer, "getOptiFineEdition", listOf(Array<String>::class.java), listOf(ofVers))
        val method = installer.getDeclaredMethod(
            "installOptiFineLibrary",
            String::class.java,
            String::class.java,
            File::class.java,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(null, mcVer, ofEd, dirMcLib, false)
        return dirMcLib
    }

    private fun <V> getMethod(
        clazz: Class<*>,
        name: String,
        parameterTypes: List<Class<*>> = ArrayList(),
        args: List<Any> = ArrayList()
    ): V {
        val m = clazz.getDeclaredMethod(name, *parameterTypes.toTypedArray())
        m.isAccessible = true
        return m.invoke(null, *args.toTypedArray()) as V
    }

    private fun tokenize(str: String?, delim: String?): Array<String> {
        val list: MutableList<String> = ArrayList()
        val tok = StringTokenizer(str, delim)
        while (tok.hasMoreTokens()) {
            val token = tok.nextToken()
            list.add(token)
        }
        return list.toTypedArray()
    }
}

data class VersionManifest(val latest: Latest, val versions: List<Version>)
data class Latest(val release: String, val snapshot: String)
data class Version(val id: String, val type: String, val url: String, val time: String, val releaseTime: String)

data class VersionJson(val downloads: Downloads)
data class Downloads(
    val client: ShaFile,
    val client_mappings: ShaFile,
    val server: ShaFile,
    val server_mappings: ShaFile
)

data class ShaFile(val sha1: String, val size: Long, val url: String)