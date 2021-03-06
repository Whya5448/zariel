package org.metalscraps.eso.lang.server

import org.metalscraps.eso.lang.lib.bean.Lang
import org.metalscraps.eso.lang.lib.config.AppConfig.langFiles
import org.metalscraps.eso.lang.lib.config.AppVariables
import org.metalscraps.eso.lang.lib.config.ESOMain
import org.metalscraps.eso.lang.lib.util.Utils
import org.metalscraps.eso.lang.server.config.ServerConfig
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.function.Predicate
import kotlin.system.exitProcess

@Component
class ServerMain(private val config: ServerConfig) : ESOMain {
    private final val logger = LoggerFactory.getLogger(ServerMain::class.java)
    private val vars = AppVariables
    private lateinit var lang: Path

    fun init(): Boolean {
        vars.run {
            baseDir = config.workDir
            lang = workDir.resolve("lang_${todayWithYear}.7z")

            for (x in dirs) if (Files.notExists(x)) Files.createDirectories(x)
            if (config.isLinux) if (Files.notExists(Paths.get("/root/.ssh/id_rsa"))) {
                logger.error("github id_rsa 존재하지 않음.")
                return false
            }
        }
        return true
    }

    @Scheduled(cron = "0 0 3 * * ?", zone = "Asia/Seoul")
    override fun start() {
        vars.run {
            reload()

            var error = false
            logger.info(dateTime.format(DateTimeFormatter.ofPattern("yy-MM-dd hh:mm:ss")) + " / 작업 시작")
            if (!init()) {
                logger.info("초기화 실패")
                exitProcess(-1)
            }

            // 이전 데이터 삭제
            logger.info("이전 데이터 삭제")
            deleteOldData()
            logger.info("PO 다운로드")
            try {
                Utils.downloadPOs()
            } catch (e: Exception) {
                logger.error(e.toString())
                e.printStackTrace();
                error = true
            }

            logger.info("LANG 생성")
            makeLANG()
            if (compress()) sfx()
            else logger.info("SFX 스킵")

            Utils.processRun(workDir, "echo ${Date().time}/$todayWithYear/${Utils.crc32(Paths.get("$lang.exe"))}", ProcessBuilder.Redirect.to(workDir.resolve("version").toFile()))
            if (!error || config.forceUpload) upload()

            // 스케쥴러 대기할 예정이므로 gc해서 메모리 비워두기
            System.gc()
        }
    }

    private fun compress(): Boolean {
        System.gc() // 외부 프로세스 사용 시 jvm oom이 안뜨므로 명시적 gc
        val needSfx = Files.notExists(lang)
        vars.run {
            logger.info("대상 압축")
            //Utils.kt.processRun(workDir, "7za a -m0=LZMA2:d96m:fb64 -mx=5 $lang $workDir/*.lang") // 최대압축/메모리 -1.5G, 아카이브 17mb
            //if (Files.notExists(lang)) Utils.processRun(workDir, "7za a -mmt=1 -m0=LZMA2:d32m:fb64 -mx=5 $lang $workDir/*.lang") // 적당히, 메모리 380m, 아카이브 30m, only 1 threads.
            if (Files.notExists(lang)) Utils.processRun(workDir, "7za a -m0=LZMA2:d96m:fb64 -mx=5 $lang $workDir/*.lang")
        }
        if (!needSfx) logger.info("압축 스킵")
        return needSfx
    }

    private fun sfx() {
        vars.run {
            logger.info("SFX 생성")
            val sfx = javaClass.classLoader.getResource("./7zCon.sfx").path
            Utils.processRun(workDir, "cat $sfx $lang", ProcessBuilder.Redirect.to(Paths.get("$lang.exe").toFile()))
        }
    }

    private fun upload() {
        vars.run {
            logger.info("목적파일 업로드")

            // 10MB 미만일 경우.
            if (Files.size(lang) < 1024 * 1024 * 10) {
                logger.error("목적파일 용량이 작음. " + Files.size(lang))
                return
            };

            // 버전 문서
            Utils.processRun(workDir, "chmod 600 /root/.ssh/id_rsa")
            Utils.processRun(workDir, "git init")
            Utils.processRun(workDir, "git add ${workDir.resolve("version")} $lang.exe")
            Utils.processRun(workDir, "git commit -m $todayWithYear")
            Utils.processRun(workDir, "git remote add origin git@github.com:Whya5448/EsoKR-LANG.git")
            Utils.processRun(workDir, "git push -u origin master --force")
            Utils.processRun(workDir, "rm .git -rf")
        }
    }

    private fun deleteOldData() {
        vars.run {
            val workDir = "$baseDir/$WORK_DIR_PREFIX"
            val p = Predicate { x: Path -> x.toString().startsWith(workDir) && !x.toString().startsWith("$workDir$today") && !x.toString().startsWith("$workDir$yesterday") }
            try {
                // 디렉토리 사용중 오류, 파일 먼저 지우고 디렉토리 지우기
                Files.walk(baseDir).filter(p.and { x -> Files.isRegularFile(x) }).forEach(Files::delete)
                Files.walk(baseDir).filter(p.and { x -> Files.isDirectory(x) }).forEach(Files::delete)
            } catch (e: IOException) {
                logger.error(e.message + " 이전 파일 삭제 실패")
                e.printStackTrace()
            }
        }
    }

    private fun makeLANG() {
        vars.run {
            val notExistLangFiles = arrayListOf<Lang>()

            langFiles.forEach { if (Files.notExists(it.file)) notExistLangFiles.add(it) }
            if (notExistLangFiles.size > 0) {
                val list = Utils.getPOList(Utils.listFiles(poDir, "po"))
                notExistLangFiles.forEach { Utils.makeLANGwithLog(it.file, list, beta = it.beta, writeFileName = it.writeFileName) }
            }
        }
    }
}
