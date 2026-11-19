package com.example.admin

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

const val ADMIN_MASTER_EMAIL = "uyghur162142@gmail.com"
const val SHARED_CRYPTO_SALT = "HESABAT_SYNC_2026_SECRET"
const val DEFAULT_CLOUD_TOPIC_URL = "https://ntfy.sh/hesabat_wolf_sync_162142_v5"

val WolfGold = Color(0xFFF5B800)
val WolfBlack = Color(0xFF0B0B0B)
val AdminIndigo = Color(0xFF6366F1)
val AdminTurquoise = Color(0xFF06B6D4)
val AdminEmerald = Color(0xFF10B981)
val AdminRose = Color(0xFFF43F5E)

enum class AdminLanguage(val code: String, val title: String) {
    UG("ug", "ئۇيغۇرچە"),
    AR("ar", "العربية")
}

fun tr(lang: AdminLanguage, ug: String, ar: String): String =
    if (lang == AdminLanguage.UG) ug else ar

enum class AccountOpenMethod(val ugShort: String, val arShort: String, val icon: ImageVector) {
    BY_GMAIL("Gmail", "Gmail", Icons.Filled.Email),
    BY_NAME("ئىسىم", "الاسم", Icons.Filled.Person),
    BY_ACCOUNT_ID("ID نامى", "المعرف", Icons.Filled.Badge)
}

enum class AdminTab(val ugTitle: String, val arTitle: String, val icon: ImageVector) {
    HOME("باش بەت", "الرئيسية", Icons.Filled.Home),
    OPEN_ACCOUNT("ھېسابات ئېچىش", "فتح حساب", Icons.Filled.PersonAdd),
    NOTICES("ئەسكەرتىش", "الإشعارات", Icons.Filled.Campaign),
    UPDATE_PUSH("نەشر يوللاش", "تحديث التطبيق", Icons.Filled.SystemUpdate),
    SETTINGS("تەڭشەكلەر", "الإعدادات", Icons.Filled.Settings)
}

enum class HomeAccountListFilter {
    NONE,
    VIP_MEMBERS,
    ALL_OPENED_ACCOUNTS
}

class AdminMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.filterTouchesWhenObscured = true
        enableEdgeToEdge()
        setContent { AdminRootScreen() }
    }
}

data class ManagedUserAccount(
    val id: String,
    val openMethod: String,
    val fullName: String,
    val username: String,
    val gmail: String,
    val password: String,
    val licenseCode: String,
    val isVip: Boolean,
    val vipExpireEpochMillis: Long,
    val status: String,
    val createdAt: String
) {
    fun isVipActive(): Boolean = isVip && (vipExpireEpochMillis == 0L || vipExpireEpochMillis > System.currentTimeMillis())
    fun vipRemainingDays(): Long {
        if (!isVip) return 0L
        if (vipExpireEpochMillis == 0L) return 9999L
        val diff = vipExpireEpochMillis - System.currentTimeMillis()
        return if (diff <= 0L) 0L else (diff / (1000L * 60L * 60L * 24L)) + 1L
    }
}

data class DirectAdminNotice(
    val id: String,
    val title: String,
    val content: String,
    val targetAccount: String,
    val sentAt: String
)

data class AppVersionRelease(
    val versionName: String,
    val apkDownloadUrl: String,
    val releaseNotes: String,
    val forceUpdate: Boolean,
    val targetAccount: String,
    val publishedAt: String
)

data class ClientAppControlConfig(
    val allowNewRegistration: Boolean,
    val requireVerificationCode: Boolean,
    val remoteMaintenanceLock: Boolean,
    val announcementBanner: String
)

class AdminStorageEngine(private val context: Context) {
    private val prefs = context.getSharedPreferences("hesabat_admin_v5_vault", Context.MODE_PRIVATE)
    private val dbFile: File get() = File(context.filesDir, "hesabat_admin_v5_accounts.json")
    private val bgFile: File get() = File(context.filesDir, "admin_custom_bg.jpg")
    private val logoFile: File get() = File(context.filesDir, "admin_custom_logo.jpg")
    val hostedApkFile: File get() = File(context.filesDir, "direct_pushed_client.apk")
    private var localApkServer: ServerSocket? = null

    fun verifyAdminPassword(input: String): Boolean {
        val savedHash = prefs.getString("admin_pwd_hash", null) ?: sha256("162142")
        return sha256(input.trim()) == savedHash
    }

    fun updateAdminPassword(newPwd: String): Boolean {
        val clean = newPwd.trim()
        if (clean.length < 4) return false
        prefs.edit().putString("admin_pwd_hash", sha256(clean)).commit()
        return true
    }

    fun getLanguage(): AdminLanguage =
        if (prefs.getString("lang", "ug") == "ar") AdminLanguage.AR else AdminLanguage.UG

    fun setLanguage(lang: AdminLanguage) {
        prefs.edit().putString("lang", lang.code).commit()
    }

    fun isDarkMode(): Boolean = prefs.getBoolean("dark_mode", true)

    fun setDarkMode(dark: Boolean) {
        prefs.edit().putBoolean("dark_mode", dark).commit()
    }

    fun getCloudSyncUrl(): String = prefs.getString("cloud_sync_url", "") ?: ""

    fun setCloudSyncUrl(url: String) {
        prefs.edit().putString("cloud_sync_url", url.trim()).commit()
    }

    fun generateVerificationCode(identifier: String, isVip: Boolean = false): String {
        val clean = identifier.trim().lowercase()
        if (clean.isEmpty()) return ""
        val raw = sha256("$SHARED_CRYPTO_SALT::$clean").uppercase()
        val prefix = if (isVip) "VIP" else "HSB"
        return "$prefix-${raw.substring(0, 4)}-${raw.substring(4, 8)}"
    }

    fun loadAccounts(): List<ManagedUserAccount> {
        val raw = runCatching { if (dbFile.exists()) dbFile.readText(Charsets.UTF_8) else "" }.getOrDefault("").ifBlank {
            prefs.getString("accounts_json", "[]") ?: "[]"
        }
        if (raw.isBlank() || raw == "[]") return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                ManagedUserAccount(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    openMethod = o.optString("openMethod", "BY_GMAIL"),
                    fullName = o.optString("fullName"),
                    username = o.optString("username"),
                    gmail = o.optString("gmail"),
                    password = o.optString("password"),
                    licenseCode = o.optString("licenseCode"),
                    isVip = o.optBoolean("isVip", false),
                    vipExpireEpochMillis = o.optLong("vipExpireEpochMillis", 0L),
                    status = o.optString("status", "ACTIVE"),
                    createdAt = o.optString("createdAt")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAccounts(list: List<ManagedUserAccount>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("id", a.id)
                    put("openMethod", a.openMethod)
                    put("fullName", a.fullName)
                    put("username", a.username)
                    put("gmail", a.gmail)
                    put("password", a.password)
                    put("licenseCode", a.licenseCode)
                    put("isVip", a.isVip)
                    put("vipExpireEpochMillis", a.vipExpireEpochMillis)
                    put("status", a.status)
                    put("createdAt", a.createdAt)
                }
            )
        }
        val text = arr.toString()
        prefs.edit().putString("accounts_json", text).commit()
        runCatching { dbFile.writeText(text, Charsets.UTF_8) }
    }

    fun loadDeletedKeys(): MutableSet<String> {
        val raw = prefs.getString("deleted_keys_json", "[]") ?: "[]"
        val out = mutableSetOf<String>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim().lowercase()
                if (s.isNotEmpty()) out.add(s)
            }
        }
        return out
    }

    fun markAccountDeleted(acc: ManagedUserAccount) {
        val set = loadDeletedKeys()
        listOf(acc.username, acc.gmail, acc.fullName).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach {
            set.add(it)
        }
        prefs.edit().putString("deleted_keys_json", JSONArray(set.toList()).toString()).commit()
    }

    fun removeFromDeletedKeys(acc: ManagedUserAccount) {
        val set = loadDeletedKeys()
        listOf(acc.username, acc.gmail, acc.fullName).map { it.trim().lowercase() }.forEach { set.remove(it) }
        prefs.edit().putString("deleted_keys_json", JSONArray(set.toList()).toString()).commit()
    }

    fun loadNotices(): List<DirectAdminNotice> {
        val raw = prefs.getString("notices_json", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                DirectAdminNotice(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    content = o.optString("content"),
                    targetAccount = o.optString("targetAccount", "ALL"),
                    sentAt = o.optString("sentAt")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveNotices(list: List<DirectAdminNotice>) {
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(
                JSONObject().apply {
                    put("id", n.id)
                    put("title", n.title)
                    put("content", n.content)
                    put("targetAccount", n.targetAccount)
                    put("sentAt", n.sentAt)
                }
            )
        }
        prefs.edit().putString("notices_json", arr.toString()).commit()
    }

    fun loadLatestRelease(): AppVersionRelease = AppVersionRelease(
        versionName = prefs.getString("rel_version", "1.1.0") ?: "1.1.0",
        apkDownloadUrl = prefs.getString("rel_url", "") ?: "",
        releaseNotes = prefs.getString("rel_notes", "") ?: "",
        forceUpdate = prefs.getBoolean("rel_force", false),
        targetAccount = prefs.getString("rel_target", "ALL") ?: "ALL",
        publishedAt = prefs.getString("rel_date", "") ?: ""
    )

    fun saveLatestRelease(rel: AppVersionRelease) {
        prefs.edit()
            .putString("rel_version", rel.versionName)
            .putString("rel_url", rel.apkDownloadUrl)
            .putString("rel_notes", rel.releaseNotes)
            .putBoolean("rel_force", rel.forceUpdate)
            .putString("rel_target", rel.targetAccount)
            .putString("rel_date", rel.publishedAt)
            .commit()
    }

    fun loadClientControlConfig(): ClientAppControlConfig = ClientAppControlConfig(
        allowNewRegistration = prefs.getBoolean("cfg_allow_reg", true),
        requireVerificationCode = prefs.getBoolean("cfg_req_code", true),
        remoteMaintenanceLock = prefs.getBoolean("cfg_remote_lock", false),
        announcementBanner = prefs.getString("cfg_banner", "") ?: ""
    )

    fun saveClientControlConfig(cfg: ClientAppControlConfig) {
        prefs.edit()
            .putBoolean("cfg_allow_reg", cfg.allowNewRegistration)
            .putBoolean("cfg_req_code", cfg.requireVerificationCode)
            .putBoolean("cfg_remote_lock", cfg.remoteMaintenanceLock)
            .putString("cfg_banner", cfg.announcementBanner)
            .commit()
    }

    fun saveBackgroundFromUri(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            bgFile.outputStream().use { out -> input.copyTo(out) }
        }
        true
    } catch (_: Exception) {
        false
    }

    fun clearBackground() {
        runCatching { if (bgFile.exists()) bgFile.delete() }
    }

    fun loadBackgroundBitmap(): ImageBitmap? = try {
        if (bgFile.exists()) BitmapFactory.decodeFile(bgFile.absolutePath)?.asImageBitmap() else null
    } catch (_: Exception) {
        null
    }

    fun saveLogoFromUri(uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            logoFile.outputStream().use { out -> input.copyTo(out) }
        }
        true
    } catch (_: Exception) {
        false
    }

    fun loadLogoBitmap(): ImageBitmap? = try {
        if (logoFile.exists()) BitmapFactory.decodeFile(logoFile.absolutePath)?.asImageBitmap() else null
    } catch (_: Exception) {
        null
    }

    suspend fun hostAndPushApkDirectly(
        apkUri: Uri,
        versionName: String,
        releaseNotes: String,
        forceUpdate: Boolean,
        targetAccount: String,
        onStatus: (String) -> Unit
    ): Result<AppVersionRelease> = withContext(Dispatchers.IO) {
        runCatching {
            withContext(Dispatchers.Main) { onStatus("APK ھۆججىتى تەييارلىنىۋاتىدۇ...") }
            context.contentResolver.openInputStream(apkUri)?.use { input ->
                hostedApkFile.outputStream().use { out -> input.copyTo(out) }
            } ?: throw IllegalArgumentException("APK ھۆججىتىنى ئوقۇغىلى بولمىدى")

            startEmbeddedDirectApkServer()
            val localIp = getDeviceLocalIpAddress()
            var directApkUrl = if (localIp.isNotBlank()) "http://$localIp:8989/HesabatClient.apk" else ""

            withContext(Dispatchers.Main) { onStatus("APK بىۋاسىتە يوللاش قانىلىغا چىقىرىلىۋاتىدۇ...") }
            val cloudUploadedUrl = uploadApkToDirectRelay(hostedApkFile)
            if (cloudUploadedUrl.isNotBlank()) directApkUrl = cloudUploadedUrl

            val now = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val release = AppVersionRelease(
                versionName = versionName.trim().ifBlank { "2.0.0" },
                apkDownloadUrl = directApkUrl,
                releaseNotes = releaseNotes.trim(),
                forceUpdate = forceUpdate,
                targetAccount = targetAccount.trim().ifBlank { "ALL" },
                publishedAt = now
            )
            saveLatestRelease(release)
            pushSyncToCloud()
            release
        }
    }

    private fun uploadApkToDirectRelay(apkFile: File): String = try {
        val boundary = "----HesabatDirectPush${System.currentTimeMillis()}"
        val conn = (URL("https://litterbox.catbox.moe/resources/internals/api.php").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            doInput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        DataOutputStream(conn.outputStream).use { out ->
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"reqtype\"\r\n\r\nfileupload\r\n")
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"time\"\r\n\r\n72h\r\n")
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"fileToUpload\"; filename=\"HesabatClient.apk\"\r\nContent-Type: application/vnd.android.package-archive\r\n\r\n")
            apkFile.inputStream().use { fileIn ->
                val buf = ByteArray(16384)
                while (true) {
                    val r = fileIn.read(buf)
                    if (r <= 0) break
                    out.write(buf, 0, r)
                }
            }
            out.writeBytes("\r\n--$boundary--\r\n")
            out.flush()
        }
        if (conn.responseCode in 200..299) {
            val resp = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            if (resp.startsWith("http")) resp else ""
        } else ""
    } catch (_: Exception) {
        ""
    }

    private fun startEmbeddedDirectApkServer() {
        if (localApkServer != null && localApkServer?.isClosed == false) return
        Thread {
            try {
                val server = ServerSocket(8989)
                localApkServer = server
                while (!server.isClosed) {
                    val client = server.accept()
                    Thread {
                        runCatching {
                            client.getOutputStream().use { rawOut ->
                                val out = DataOutputStream(rawOut)
                                if (hostedApkFile.exists()) {
                                    out.writeBytes("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: ${hostedApkFile.length()}\r\nConnection: close\r\n\r\n")
                                    hostedApkFile.inputStream().use { f ->
                                        val b = ByteArray(16384)
                                        while (true) {
                                            val r = f.read(b)
                                            if (r <= 0) break
                                            out.write(b, 0, r)
                                        }
                                    }
                                    out.flush()
                                }
                            }
                            client.close()
                        }
                    }.start()
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun getDeviceLocalIpAddress(): String = try {
        val ifs = NetworkInterface.getNetworkInterfaces()
        var found = ""
        while (ifs.hasMoreElements()) {
            val addrs = ifs.nextElement().inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (!a.isLoopbackAddress && a is Inet4Address) {
                    found = a.hostAddress ?: ""
                    break
                }
            }
            if (found.isNotEmpty()) break
        }
        found
    } catch (_: Exception) {
        ""
    }

    fun buildSyncPayloadJson(): String {
        val rel = loadLatestRelease()
        val cfg = loadClientControlConfig()
        val accountsArr = JSONArray(prefs.getString("accounts_json", "[]") ?: "[]")
        val noticesArr = JSONArray(prefs.getString("notices_json", "[]") ?: "[]")
        val deletedArr = JSONArray(prefs.getString("deleted_keys_json", "[]") ?: "[]")
        val clearEpoch = prefs.getLong("clear_all_epoch", 0L)
        return JSONObject().apply {
            put("adminMasterEmail", ADMIN_MASTER_EMAIL)
            put("syncedAt", System.currentTimeMillis())
            put("clearAllEpoch", clearEpoch)
            put("accounts", accountsArr)
            put("deletedKeys", deletedArr)
            put("notices", noticesArr)
            put("appUpdate", JSONObject().apply {
                put("versionName", rel.versionName)
                put("apkDownloadUrl", rel.apkDownloadUrl)
                put("releaseNotes", rel.releaseNotes)
                put("forceUpdate", rel.forceUpdate)
                put("targetAccount", rel.targetAccount)
                put("publishedAt", rel.publishedAt)
            })
            put("clientConfig", JSONObject().apply {
                put("allowNewRegistration", cfg.allowNewRegistration)
                put("requireVerificationCode", cfg.requireVerificationCode)
                put("remoteMaintenanceLock", cfg.remoteMaintenanceLock)
                put("announcementBanner", cfg.announcementBanner)
            })
        }.toString()
    }

    suspend fun pushSyncToCloud(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildSyncPayloadJson()
            val customUrl = getCloudSyncUrl().trim()
            if (customUrl.isNotEmpty()) {
                val targetUrl = if (customUrl.endsWith(".json")) customUrl else "$customUrl/hesabat_sync.json"
                val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    connectTimeout = 6000
                    readTimeout = 6000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } else {
                val conn = (URL(DEFAULT_CLOUD_TOPIC_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 6000
                    readTimeout = 6000
                    doOutput = true
                    setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            }
            "SYNCED"
        }
    }

    fun clearAllData() {
        prefs.edit()
            .putString("accounts_json", "[]")
            .putString("deleted_keys_json", "[]")
            .putLong("clear_all_epoch", System.currentTimeMillis())
            .commit()
        runCatching { if (dbFile.exists()) dbFile.delete() }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

@Composable
fun AdminBrandLogo(customLogo: ImageBitmap?, sizeDp: Int = 72) {
    if (customLogo != null) {
        Image(
            bitmap = customLogo,
            contentDescription = "Logo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape((sizeDp / 3).dp)).border(2.dp, WolfGold, RoundedCornerShape((sizeDp / 3).dp))
        )
    } else {
        Image(
            painter = painterResource(id = R.drawable.ic_admin_logo),
            contentDescription = "Wolf Logo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape((sizeDp / 3).dp)).border(2.dp, WolfGold, RoundedCornerShape((sizeDp / 3).dp))
        )
    }
}

@Composable
fun AdminRootScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val storage = remember { AdminStorageEngine(context.applicationContext) }

    var lang by remember { mutableStateOf(storage.getLanguage()) }
    var isDark by remember { mutableStateOf(storage.isDarkMode()) }
    var isAuthenticated by remember { mutableStateOf(false) }

    var currentTab by remember { mutableStateOf(AdminTab.HOME) }
    var homeFilterOverlay by remember { mutableStateOf(HomeAccountListFilter.NONE) }
    var searchQuery by remember { mutableStateOf("") }

    var accounts by remember { mutableStateOf(storage.loadAccounts()) }
    var notices by remember { mutableStateOf(storage.loadNotices()) }
    var latestRelease by remember { mutableStateOf(storage.loadLatestRelease()) }
    var clientConfig by remember { mutableStateOf(storage.loadClientControlConfig()) }

    var customBg by remember { mutableStateOf(storage.loadBackgroundBitmap()) }
    var customLogo by remember { mutableStateOf(storage.loadLogoBitmap()) }

    val bgColor = if (isDark) Color(0xFF070B16) else Color(0xFFF8FAFC)
    val cardColor = if (isDark) Color(0xE6111A2E) else Color(0xF2FFFFFF)
    val textColor = if (isDark) Color.White else Color(0xFF0F172A)

    val saveAndSyncAccounts: (List<ManagedUserAccount>) -> Unit = { updated ->
        accounts = updated
        storage.saveAccounts(updated)
        scope.launch { storage.pushSyncToCloud() }
    }

    // قارشى تەرەپنىڭ Gmail ئادرېسىغا دەل چۈشىدىغان قىلىپ تۈزىتىلگەن ئېلخەت فۇنكسىيەسى
    val sendCodeToRecipientGmail: (String, String, String) -> Unit = { recipientEmail, subject, body ->
        val cleanTo = recipientEmail.trim()
        if (!cleanTo.contains("@") || !cleanTo.contains(".")) {
            scope.launch {
                snackbarHostState.showSnackbar(tr(lang, "قارشى تەرەپنىڭ Gmail ئادرېسى توغرا ئەمەس!", "عنوان Gmail للمستلم غير صحيح!"))
            }
        } else {
            runCatching {
                val gmailIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(cleanTo))
                    putExtra(Intent.EXTRA_BCC, arrayOf(ADMIN_MASTER_EMAIL))
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, body)
                    setPackage("com.google.android.gm")
                }
                context.startActivity(gmailIntent)
            }.onFailure {
                runCatching {
                    val fallback = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:")
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(cleanTo))
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                        putExtra(Intent.EXTRA_TEXT, body)
                    }
                    context.startActivity(Intent.createChooser(fallback, tr(lang, "Gmail ئارقىلىق يوللاش", "إرسال عبر Gmail")))
                }
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = if (isDark) {
                darkColorScheme(primary = WolfGold, secondary = AdminTurquoise, background = bgColor, surface = cardColor, onSurface = textColor, onBackground = textColor)
            } else {
                lightColorScheme(primary = AdminIndigo, secondary = AdminTurquoise, background = bgColor, surface = cardColor, onSurface = textColor, onBackground = textColor)
            }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (customBg != null) {
                    Image(bitmap = customBg!!, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(modifier = Modifier.fillMaxSize().background(if (isDark) Color(0xAA050914) else Color(0x88FFFFFF)))
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                if (isDark) listOf(Color(0xFF070B16), Color(0xFF101935), Color(0xFF060913))
                                else listOf(Color(0xFFFEF9C3), Color(0xFFF1F5F9), Color(0xFFE2E8F0))
                            )
                        )
                    )
                }

                if (!isAuthenticated) {
                    AdminCleanLoginScreen(
                        lang = lang,
                        cardColor = cardColor,
                        textColor = textColor,
                        customLogo = customLogo,
                        onSwitchLang = { newLang ->
                            lang = newLang
                            storage.setLanguage(newLang)
                        },
                        onVerify = { inputPwd ->
                            val ok = storage.verifyAdminPassword(inputPwd)
                            if (ok) isAuthenticated = true
                            ok
                        }
                    )
                } else {
                    if (homeFilterOverlay != HomeAccountListFilter.NONE) {
                        BackHandler { homeFilterOverlay = HomeAccountListFilter.NONE }
                        HomeFilteredAccountsPage(
                            lang = lang,
                            filter = homeFilterOverlay,
                            accounts = accounts,
                            searchQuery = searchQuery,
                            cardColor = cardColor,
                            textColor = textColor,
                            onSearchChange = { searchQuery = it },
                            onBack = { homeFilterOverlay = HomeAccountListFilter.NONE },
                            onCopyCode = { code ->
                                clipboard.setText(AnnotatedString(code))
                                scope.launch { snackbarHostState.showSnackbar(tr(lang, "كود كۆچۈرۈلدى: $code", "تم نسخ الرمز: $code")) }
                            },
                            onSendGmail = { acc ->
                                sendCodeToRecipientGmail(
                                    acc.gmail,
                                    tr(lang, "Hesabat ھېسابات دەلىللەش كودىڭىز", "رمز تفعيل حساب Hesabat"),
                                    buildString {
                                        appendLine(tr(lang, "ھۆرمەتلىك ${acc.fullName}،", "عزيزي ${acc.fullName}،"))
                                        appendLine("• ${tr(lang, "ھېسابات نامى", "اسم الحساب")}: ${acc.username}")
                                        appendLine("• ${tr(lang, "مەخپىي نومۇر", "كلمة المرور")}: ${acc.password}")
                                        appendLine("• ${tr(lang, "دەلىللەش كودى", "رمز التفعيل")}: ${acc.licenseCode}")
                                    }
                                )
                            },
                            onUpdateVipDays = { targetAcc, days ->
                                val isVipNow = days > 0
                                val expireMillis = if (isVipNow) System.currentTimeMillis() + days.toLong() * 24L * 3600L * 1000L else 0L
                                val updatedCode = storage.generateVerificationCode(targetAcc.username, isVipNow)
                                saveAndSyncAccounts(
                                    accounts.map {
                                        if (it.id == targetAcc.id) it.copy(isVip = isVipNow, vipExpireEpochMillis = expireMillis, status = if (isVipNow) "VIP" else "ACTIVE", licenseCode = updatedCode)
                                        else it
                                    }
                                )
                            },
                            onDelete = { acc ->
                                storage.markAccountDeleted(acc)
                                saveAndSyncAccounts(accounts.filterNot { it.id == acc.id })
                                scope.launch {
                                    snackbarHostState.showSnackbar(tr(lang, "«${acc.fullName}» ئۆچۈرۈلدى ۋە ھېسابات ئەپىدىن چېكىندۈرۈلدى!", "تم حذف «${acc.fullName}» وتسجيل خروجه مباشرة!"))
                                }
                            }
                        )
                    } else {
                        Scaffold(
                            containerColor = Color.Transparent,
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                            bottomBar = {
                                NavigationBar(containerColor = if (isDark) Color(0xF00B1120) else Color(0xF0FFFFFF), tonalElevation = 10.dp) {
                                    AdminTab.entries.forEach { tab ->
                                        NavigationBarItem(
                                            selected = currentTab == tab,
                                            onClick = { currentTab = tab },
                                            icon = { Icon(tab.icon, contentDescription = null) },
                                            label = { Text(tr(lang, tab.ugTitle, tab.arTitle), style = MaterialTheme.typography.labelSmall, fontWeight = if (currentTab == tab) FontWeight.ExtraBold else FontWeight.Normal) }
                                        )
                                    }
                                }
                            }
                        ) { innerPadding ->
                            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp)) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    color = cardColor,
                                    border = BorderStroke(1.2.dp, WolfGold.copy(alpha = 0.55f))
                                ) {
                                    Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                                            AdminBrandLogo(customLogo = customLogo, sizeDp = 46)
                                            Column {
                                                Text(tr(lang, "باش باشقۇرغۇچى سۇپىسى", "لوحة تحكم المسؤول"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = textColor)
                                                Text(ADMIN_MASTER_EMAIL, style = MaterialTheme.typography.labelSmall, color = WolfGold, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(onClick = { isDark = !isDark; storage.setDarkMode(isDark) }) {
                                                Icon(if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode, contentDescription = null, tint = WolfGold)
                                            }
                                            IconButton(onClick = { isAuthenticated = false }) {
                                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = AdminRose)
                                            }
                                        }
                                    }
                                }

                                when (currentTab) {
                                    AdminTab.HOME -> {
                                        AdminCleanHomeTab(
                                            lang = lang,
                                            accounts = accounts,
                                            searchQuery = searchQuery,
                                            cardColor = cardColor,
                                            textColor = textColor,
                                            onSearchChange = { searchQuery = it },
                                            onTriggerSearch = { homeFilterOverlay = HomeAccountListFilter.ALL_OPENED_ACCOUNTS },
                                            onOpenVipMembers = { searchQuery = ""; homeFilterOverlay = HomeAccountListFilter.VIP_MEMBERS },
                                            onOpenAllAccounts = { searchQuery = ""; homeFilterOverlay = HomeAccountListFilter.ALL_OPENED_ACCOUNTS },
                                            onNavigateTab = { currentTab = it }
                                        )
                                    }

                                    AdminTab.OPEN_ACCOUNT -> {
                                        AdminSleekOpenAccountTab(
                                            lang = lang,
                                            storage = storage,
                                            cardColor = cardColor,
                                            textColor = textColor,
                                            onShowWarning = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                                            onAccountCreated = { newAcc, sendEmailNow ->
                                                storage.removeFromDeletedKeys(newAcc)
                                                saveAndSyncAccounts(listOf(newAcc) + accounts)
                                                clipboard.setText(AnnotatedString(newAcc.licenseCode))
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(tr(lang, "ھېسابات ئېچىلدى ۋە كودى كۆچۈرۈلدى: ${newAcc.licenseCode}", "تم فتح الحساب ونسخ الرمز: ${newAcc.licenseCode}"))
                                                }
                                                if (sendEmailNow) {
                                                    sendCodeToRecipientGmail(
                                                        newAcc.gmail,
                                                        tr(lang, "Hesabat ھېسابات ۋە دەلىللەش كودىڭىز", "حساب Hesabat ورمز التفعيل"),
                                                        buildString {
                                                            appendLine(tr(lang, "ئەسسالامۇئەلەيكۇم ${newAcc.fullName}،", "السلام عليكم ${newAcc.fullName}،"))
                                                            appendLine("• ${tr(lang, "ھېسابات نامى", "اسم الحساب")}: ${newAcc.username}")
                                                            appendLine("• ${tr(lang, "مەخپىي نومۇر", "كلمة المرور")}: ${newAcc.password}")
                                                            appendLine("• ${tr(lang, "دەلىللەش كودى", "رمز التفعيل")}: ${newAcc.licenseCode}")
                                                        }
                                                    )
                                                }
                                            }
                                        )
                                    }

                                    AdminTab.NOTICES -> {
                                        AdminDirectNoticePushTab(
                                            lang = lang,
                                            notices = notices,
                                            cardColor = cardColor,
                                            textColor = textColor,
                                            onPushDirectNotice = { title, content, target ->
                                                val item = DirectAdminNotice(
                                                    id = UUID.randomUUID().toString(),
                                                    title = title,
                                                    content = content,
                                                    targetAccount = target.ifBlank { "ALL" },
                                                    sentAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                                                )
                                                val updated = listOf(item) + notices
                                                notices = updated
                                                storage.saveNotices(updated)
                                                scope.launch {
                                                    storage.pushSyncToCloud()
                                                    snackbarHostState.showSnackbar(tr(lang, "ئەسكەرتىش بىۋاسىتە ھېسابات ئەپىگە يوللاندى!", "تم إرسال الإشعار مباشرة لتطبيق الحسابات!"))
                                                }
                                            }
                                        )
                                    }

                                    AdminTab.UPDATE_PUSH -> {
                                        AdminDirectApkPushTab(
                                            lang = lang,
                                            storage = storage,
                                            latestRelease = latestRelease,
                                            cardColor = cardColor,
                                            textColor = textColor,
                                            onReleaseUpdated = { updatedRel ->
                                                latestRelease = updatedRel
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(tr(lang, "يېڭى نەشر (${updatedRel.versionName}) بىۋاسىتە ھېسابات ئەپىگە يوللاندى!", "تم إرسال التحديث (${updatedRel.versionName}) مباشرة!"))
                                                }
                                            }
                                        )
                                    }

                                    AdminTab.SETTINGS -> {
                                        AdminComprehensiveSettingsTab(
                                            lang = lang,
                                            isDark = isDark,
                                            storage = storage,
                                            clientConfig = clientConfig,
                                            cardColor = cardColor,
                                            textColor = textColor,
                                            onLangChange = { newLang -> lang = newLang; storage.setLanguage(newLang) },
                                            onDarkChange = { dark -> isDark = dark; storage.setDarkMode(dark) },
                                            onPickBackground = { uri -> if (storage.saveBackgroundFromUri(uri)) customBg = storage.loadBackgroundBitmap() },
                                            onResetBackground = { storage.clearBackground(); customBg = null },
                                            onPickLogo = { uri -> if (storage.saveLogoFromUri(uri)) customLogo = storage.loadLogoBitmap() },
                                            onSaveClientConfig = { newCfg ->
                                                clientConfig = newCfg
                                                storage.saveClientControlConfig(newCfg)
                                                scope.launch {
                                                    storage.pushSyncToCloud()
                                                    snackbarHostState.showSnackbar(tr(lang, "تەڭشەكلەر بىۋاسىتە ھېسابات ئەپىگە يوللاندى!", "تم إرسال الإعدادات مباشرة لتطبيق الحسابات!"))
                                                }
                                            },
                                            onClearAllAccounts = {
                                                storage.clearAllData()
                                                accounts = emptyList()
                                                scope.launch {
                                                    storage.pushSyncToCloud()
                                                    snackbarHostState.showSnackbar(tr(lang, "بارلىق ھېساباتلار ئۆچۈرۈلدى ۋە ھېسابات ئەپى چېكىندۈرۈلدى!", "تم مسح جميع الحسابات وتسجيل الخروج!"))
                                                }
                                            },
                                            onShowNotice = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminCleanLoginScreen(
    lang: AdminLanguage,
    cardColor: Color,
    textColor: Color,
    customLogo: ImageBitmap?,
    onSwitchLang: (AdminLanguage) -> Unit,
    onVerify: (String) -> Boolean
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
            shape = RoundedCornerShape(30.dp),
            color = cardColor,
            border = BorderStroke(1.8.dp, WolfGold)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    AdminLanguage.entries.forEach { l ->
                        FilterChip(selected = lang == l, onClick = { onSwitchLang(l) }, label = { Text(l.title) }, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                AdminBrandLogo(customLogo = customLogo, sizeDp = 86)
                Text(tr(lang, "باشقۇرغۇچى مەركىزى", "مركز تحكم المسؤول"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = textColor)
                Text(ADMIN_MASTER_EMAIL, style = MaterialTheme.typography.labelMedium, color = WolfGold, fontWeight = FontWeight.Bold)

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorText = null },
                    label = { Text(tr(lang, "باشقۇرغۇچى مەخپىي نومۇرى", "كلمة مرور المسؤول")) },
                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = WolfGold) },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null)
                        }
                    },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorText != null) {
                    Text(errorText.orEmpty(), color = AdminRose, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { if (!onVerify(password)) errorText = tr(lang, "مەخپىي نومۇر خاتا!", "كلمة المرور غير صحيحة!") },
                    colors = ButtonDefaults.buttonColors(containerColor = WolfGold),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Filled.Login, contentDescription = null, tint = WolfBlack)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(tr(lang, "دەلىللەپ كىرىش", "تسجيل الدخول"), color = WolfBlack, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
fun AdminCleanHomeTab(
    lang: AdminLanguage,
    accounts: List<ManagedUserAccount>,
    searchQuery: String,
    cardColor: Color,
    textColor: Color,
    onSearchChange: (String) -> Unit,
    onTriggerSearch: () -> Unit,
    onOpenVipMembers: () -> Unit,
    onOpenAllAccounts: () -> Unit,
    onNavigateTab: (AdminTab) -> Unit
) {
    val vipCount = accounts.count { it.isVipActive() }
    val totalOpenedCount = accounts.size

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 84.dp)) {
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = cardColor, border = BorderStroke(1.dp, WolfGold.copy(alpha = 0.35f))) {
                Column(modifier = Modifier.padding(14.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        placeholder = { Text(tr(lang, "Gmail، ئىسىم ياكى ھېسابات نامى بويىچە ئىزدەش...", "البحث عبر Gmail أو الاسم أو الحساب...")) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = WolfGold) },
                        trailingIcon = {
                            Button(
                                onClick = onTriggerSearch,
                                colors = ButtonDefaults.buttonColors(containerColor = WolfGold),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.padding(end = 6.dp)
                            ) {
                                Text(tr(lang, "ئىزدەش", "بحث"), color = WolfBlack, fontWeight = FontWeight.ExtraBold)
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeSymbolCard(tr(lang, "VIP ئەزالار", "أعضاء VIP"), tr(lang, "چېكىپ تىزىملىكنى كۆرۈش", "اضغط لعرض القائمة"), vipCount, Icons.Filled.WorkspacePremium, WolfGold, cardColor, textColor, Modifier.weight(1f), onOpenVipMembers)
                HomeSymbolCard(tr(lang, "ھېسابات ئاچقانلار", "الحسابات المفتوحة"), tr(lang, "چېكىپ تىزىملىكنى كۆرۈش", "اضغط لعرض القائمة"), totalOpenedCount, Icons.Filled.VerifiedUser, AdminTurquoise, cardColor, textColor, Modifier.weight(1f), onOpenAllAccounts)
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HomeSymbolCard(tr(lang, "ھېسابات ئېچىپ بېرىش", "فتح حساب جديد"), tr(lang, "Gmail / ئىسىم / ID", "Gmail / الاسم / المعرف"), null, Icons.Filled.PersonAdd, AdminEmerald, cardColor, textColor, Modifier.weight(1f)) { onNavigateTab(AdminTab.OPEN_ACCOUNT) }
                HomeSymbolCard(tr(lang, "ئەسكەرتىش يوللاش", "إرسال إشعار مباشر"), tr(lang, "بىۋاسىتە ھېسابات ئەپىگە", "مباشرة لتطبيق الحسابات"), null, Icons.Filled.Campaign, AdminIndigo, cardColor, textColor, Modifier.weight(1f)) { onNavigateTab(AdminTab.NOTICES) }
            }
        }
    }
}

@Composable
fun HomeSymbolCard(
    title: String,
    subtitle: String,
    count: Int?,
    icon: ImageVector,
    accent: Color,
    cardColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(modifier = modifier.clickable { onClick() }, shape = RoundedCornerShape(24.dp), color = cardColor, border = BorderStroke(1.5.dp, accent.copy(alpha = 0.6f))) {
        Column(modifier = Modifier.padding(vertical = 20.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(accent.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = title, tint = accent, modifier = Modifier.size(32.dp))
            }
            if (count != null) Text("$count", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = accent)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = textColor, textAlign = TextAlign.Center)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = accent, textAlign = TextAlign.Center)
        }
    }
}

/**
 * ئورۇن-جاي كاتەكچىسى چىقىرىۋېتىلگەن، گۈزەل ۋە ئىخچام ھېسابات ئېچىپ بېرىش كۆزنىكى
 */
@Composable
fun AdminSleekOpenAccountTab(
    lang: AdminLanguage,
    storage: AdminStorageEngine,
    cardColor: Color,
    textColor: Color,
    onShowWarning: (String) -> Unit,
    onAccountCreated: (ManagedUserAccount, Boolean) -> Unit
) {
    var selectedMethod by remember { mutableStateOf(AccountOpenMethod.BY_GMAIL) }
    var gmailInput by remember { mutableStateOf("") }
    var fullNameInput by remember { mutableStateOf("") }
    var accountIdInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var grantVip by remember { mutableStateOf(false) }
    var vipDays by remember { mutableIntStateOf(30) }

    val primaryIdentifier = when (selectedMethod) {
        AccountOpenMethod.BY_GMAIL -> gmailInput.trim()
        AccountOpenMethod.BY_NAME -> fullNameInput.trim()
        AccountOpenMethod.BY_ACCOUNT_ID -> accountIdInput.trim()
    }

    val liveGeneratedCode = remember(primaryIdentifier, grantVip) {
        storage.generateVerificationCode(primaryIdentifier, grantVip)
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 84.dp)) {
        item {
            Surface(shape = RoundedCornerShape(26.dp), color = cardColor, border = BorderStroke(1.5.dp, WolfGold.copy(alpha = 0.55f))) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.PersonAdd, contentDescription = null, tint = WolfGold)
                        Text(tr(lang, "يېڭى ھېسابات ئېچىپ بېرىش", "فتح حساب جديد للمستخدم"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = textColor)
                    }

                    // چىرايلىق بۆلەكلىك تاللاش تاختىسى (Segmented Pill Bar)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, WolfGold.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            AccountOpenMethod.entries.forEach { method ->
                                val active = selectedMethod == method
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (active) WolfGold else Color.Transparent,
                                    modifier = Modifier.weight(1f).clickable { selectedMethod = method }
                                ) {
                                    Row(modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                        Icon(method.icon, contentDescription = null, tint = if (active) WolfBlack else textColor.copy(alpha = 0.75f), modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(tr(lang, method.ugShort, method.arShort), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = if (active) WolfBlack else textColor)
                                    }
                                }
                            }
                        }
                    }

                    if (selectedMethod == AccountOpenMethod.BY_NAME) {
                        OutlinedTextField(
                            value = fullNameInput,
                            onValueChange = { fullNameInput = it },
                            label = { Text(tr(lang, "خېرىدارنىڭ تولۇق ئىسمى *", "الاسم الكامل للعميل *")) },
                            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, tint = WolfGold) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else if (selectedMethod == AccountOpenMethod.BY_ACCOUNT_ID) {
                        OutlinedTextField(
                            value = accountIdInput,
                            onValueChange = { accountIdInput = it },
                            label = { Text(tr(lang, "مەخسۇس ھېسابات نامى (ID) *", "معرف الحساب (ID) *")) },
                            leadingIcon = { Icon(Icons.Filled.Badge, contentDescription = null, tint = WolfGold) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = fullNameInput,
                            onValueChange = { fullNameInput = it },
                            label = { Text(tr(lang, "خېرىدارنىڭ ئىسمى", "اسم العميل")) },
                            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, tint = AdminTurquoise) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = fullNameInput,
                            onValueChange = { fullNameInput = it },
                            label = { Text(tr(lang, "خېرىدارنىڭ ئىسمى", "اسم العميل")) },
                            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, tint = AdminTurquoise) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    OutlinedTextField(
                        value = gmailInput,
                        onValueChange = { gmailInput = it },
                        label = {
                            Text(
                                if (selectedMethod == AccountOpenMethod.BY_GMAIL) tr(lang, "قارشى تەرەپنىڭ Gmail ئادرېسى *", "عنوان Gmail للعميل *")
                                else tr(lang, "قارشى تەرەپنىڭ Gmail ئادرېسى (كود ئەۋەتىش ئۈچۈن)", "عنوان Gmail للعميل (لإرسال الرمز)")
                            )
                        },
                        placeholder = { Text("client@gmail.com") },
                        leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null, tint = WolfGold) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text(tr(lang, "ھېسابات مەخپىي نومۇرى *", "كلمة مرور الحساب *")) },
                        leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null, tint = AdminEmerald) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(lang, "VIP ھوقۇقى بىلەن ئېچىش", "تفعيل عضوية VIP"), fontWeight = FontWeight.Bold, color = textColor)
                        Switch(checked = grantVip, onCheckedChange = { grantVip = it })
                    }

                    if (grantVip) {
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(7 to "7 كۈن", 30 to "1 ئاي", 90 to "3 ئاي", 365 to "1 يىل").forEach { (d, label) ->
                                FilterChip(selected = vipDays == d, onClick = { vipDays = d }, label = { Text(label) })
                            }
                        }
                    }

                    if (liveGeneratedCode.isNotBlank()) {
                        Surface(shape = RoundedCornerShape(16.dp), color = AdminEmerald.copy(alpha = 0.16f), border = BorderStroke(1.2.dp, AdminEmerald), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(tr(lang, "دەلىللەش كودى:", "رمز التفعيل:"), style = MaterialTheme.typography.labelSmall, color = textColor)
                                Text(liveGeneratedCode, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = AdminEmerald)
                            }
                        }
                    }

                    val submitAccount: (Boolean) -> Unit = { sendEmail ->
                        if (primaryIdentifier.isBlank()) {
                            onShowWarning(tr(lang, "ئالدى بىلەن ھېسابات ئۇچۇرىنى تولدۇرۇڭ!", "يرجى إدخال بيانات الحساب أولاً!"))
                        } else if (sendEmail && (!gmailInput.contains("@") || !gmailInput.contains("."))) {
                            onShowWarning(tr(lang, "كود ئەۋەتىش ئۈچۈن قارشى تەرەپنىڭ توغرا Gmail ئادرېسىنى يېزىڭ!", "يرجى إدخال عنوان Gmail صحيح للعميل!"))
                        } else {
                            val today = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                            val finalGmail = if (gmailInput.contains("@")) gmailInput.trim() else "$primaryIdentifier@gmail.com"
                            val newAcc = ManagedUserAccount(
                                id = UUID.randomUUID().toString(),
                                openMethod = selectedMethod.name,
                                fullName = fullNameInput.trim().ifBlank { primaryIdentifier },
                                username = primaryIdentifier,
                                gmail = finalGmail,
                                password = passwordInput.trim().ifBlank { "123456" },
                                licenseCode = liveGeneratedCode,
                                isVip = grantVip,
                                vipExpireEpochMillis = if (grantVip) System.currentTimeMillis() + vipDays.toLong() * 24L * 3600L * 1000L else 0L,
                                status = if (grantVip) "VIP" else "ACTIVE",
                                createdAt = today
                            )
                            onAccountCreated(newAcc, sendEmail)
                            gmailInput = ""
                            fullNameInput = ""
                            accountIdInput = ""
                            passwordInput = ""
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { submitAccount(false) }, colors = ButtonDefaults.buttonColors(containerColor = AdminEmerald), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).height(50.dp)) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(tr(lang, "ھېسابات ئېچىش", "فتح الحساب"), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Button(onClick = { submitAccount(true) }, colors = ButtonDefaults.buttonColors(containerColor = WolfGold), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f).height(50.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = WolfBlack)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(tr(lang, "Gmail غا يوللاش", "إرسال لـ Gmail"), color = WolfBlack, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminDirectNoticePushTab(
    lang: AdminLanguage,
    notices: List<DirectAdminNotice>,
    cardColor: Color,
    textColor: Color,
    onPushDirectNotice: (String, String, String) -> Unit
) {
    var noticeTitle by remember { mutableStateOf("") }
    var noticeBody by remember { mutableStateOf("") }
    var targetAccount by remember { mutableStateOf("ALL") }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 84.dp)) {
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = cardColor, border = BorderStroke(1.6.dp, WolfGold)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Filled.Campaign, contentDescription = null, tint = WolfGold, modifier = Modifier.size(30.dp))
                        Column {
                            Text(tr(lang, "بىۋاسىتە ئەسكەرتىش يوللاش", "إرسال إشعار مباشر لتطبيق الحسابات"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = textColor)
                            Text(tr(lang, "Gmail ئاچماستىن بىۋاسىتە ھېسابات ئەپىگە ئۇقتۇرۇش چۈشىدۇ", "يصل الإشعار مباشرة داخل تطبيق الحسابات"), style = MaterialTheme.typography.labelSmall, color = WolfGold)
                        }
                    }
                    OutlinedTextField(value = noticeTitle, onValueChange = { noticeTitle = it }, label = { Text(tr(lang, "ئەسكەرتىش ماۋزۇسى *", "عنوان الإشعار *")) }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = noticeBody, onValueChange = { noticeBody = it }, label = { Text(tr(lang, "ئەسكەرتىش مەزمۇنى *", "نص الإشعار *")) }, minLines = 3, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = targetAccount, onValueChange = { targetAccount = it }, label = { Text(tr(lang, "قوبۇل قىلغۇچى (ALL = ھەممەيلەن)", "المستلم (ALL = الجميع)")) }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            if (noticeBody.isNotBlank()) {
                                onPushDirectNotice(noticeTitle.ifBlank { tr(lang, "باشقۇرغۇچى ئەسكەرتىشى", "تنبيه من المسؤول") }, noticeBody.trim(), targetAccount.trim())
                                noticeTitle = ""
                                noticeBody = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WolfGold),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = WolfBlack)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tr(lang, "ۋاسىتىسىز بىۋاسىتە يوللاش", "إرسال الإشعار مباشرة"), color = WolfBlack, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
        items(notices, key = { it.id }) { item ->
            Surface(shape = RoundedCornerShape(18.dp), color = cardColor, border = BorderStroke(1.dp, AdminTurquoise.copy(alpha = 0.4f))) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(item.title, fontWeight = FontWeight.ExtraBold, color = WolfGold)
                        Text(item.sentAt, style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.6f))
                    }
                    Text(item.content, style = MaterialTheme.typography.bodyMedium, color = textColor)
                }
            }
        }
    }
}

@Composable
fun AdminDirectApkPushTab(
    lang: AdminLanguage,
    storage: AdminStorageEngine,
    latestRelease: AppVersionRelease,
    cardColor: Color,
    textColor: Color,
    onReleaseUpdated: (AppVersionRelease) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var versionName by remember { mutableStateOf(latestRelease.versionName) }
    var notes by remember { mutableStateOf(latestRelease.releaseNotes) }
    var forceUpdate by remember { mutableStateOf(latestRelease.forceUpdate) }
    var targetAccount by remember { mutableStateOf(latestRelease.targetAccount) }
    var selectedApkUri by remember { mutableStateOf<Uri?>(null) }
    var selectedApkName by remember { mutableStateOf("") }
    var isPushingNow by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedApkUri = uri
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) selectedApkName = c.getString(idx) ?: "HesabatClient.apk"
                    }
                }
            }
            if (selectedApkName.isBlank()) selectedApkName = "HesabatClient.apk"
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 84.dp)) {
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = cardColor, border = BorderStroke(1.6.dp, WolfGold)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(tr(lang, "ئەپ يېڭى نەشرىنى بىۋاسىتە يوللاش", "إرسال تحديث التطبيق مباشرة"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = textColor)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (selectedApkUri != null) AdminEmerald.copy(alpha = 0.16f) else WolfGold.copy(alpha = 0.14f),
                        border = BorderStroke(1.5.dp, if (selectedApkUri != null) AdminEmerald else WolfGold),
                        modifier = Modifier.fillMaxWidth().clickable { apkPicker.launch("*/*") }
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (selectedApkUri != null) selectedApkName else tr(lang, "تېلېفوندىن APK ھۆججىتىنى تاللاش *", "اختر ملف APK من الهاتف *"), fontWeight = FontWeight.Bold, color = textColor)
                            Icon(Icons.Filled.UploadFile, contentDescription = null, tint = WolfGold)
                        }
                    }
                    OutlinedTextField(value = versionName, onValueChange = { versionName = it }, label = { Text(tr(lang, "يېڭى نەشر نومۇرى", "رقم الإصدار")) }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text(tr(lang, "يېڭىلىقلار", "ملاحظات التحديث")) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = targetAccount, onValueChange = { targetAccount = it }, label = { Text(tr(lang, "قوبۇل قىلغۇچى (ALL = ھەممەيلەن)", "المستهدف (ALL = الجميع)")) }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                    if (statusText.isNotBlank()) Text(statusText, color = AdminTurquoise, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            if (selectedApkUri != null && !isPushingNow) {
                                scope.launch {
                                    isPushingNow = true
                                    val res = storage.hostAndPushApkDirectly(selectedApkUri!!, versionName, notes, forceUpdate, targetAccount) { statusText = it }
                                    isPushingNow = false
                                    res.onSuccess { statusText = tr(lang, "✅ مۇۋەپپەقىيەتلىك يوللاندى!", "✅ تم الإرسال بنجاح!"); onReleaseUpdated(it) }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WolfGold),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        Text(if (isPushingNow) tr(lang, "يوللىنىۋاتىدۇ...", "جاري الإرسال...") else tr(lang, "ۋاسىتىسىز بىۋاسىتە يوللاش", "إرسال مباشر"), color = WolfBlack, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }
}

@Composable
fun AdminComprehensiveSettingsTab(
    lang: AdminLanguage,
    isDark: Boolean,
    storage: AdminStorageEngine,
    clientConfig: ClientAppControlConfig,
    cardColor: Color,
    textColor: Color,
    onLangChange: (AdminLanguage) -> Unit,
    onDarkChange: (Boolean) -> Unit,
    onPickBackground: (Uri) -> Unit,
    onResetBackground: () -> Unit,
    onPickLogo: (Uri) -> Unit,
    onSaveClientConfig: (ClientAppControlConfig) -> Unit,
    onClearAllAccounts: () -> Unit,
    onShowNotice: (String) -> Unit
) {
    var allowReg by remember { mutableStateOf(clientConfig.allowNewRegistration) }
    var reqCode by remember { mutableStateOf(clientConfig.requireVerificationCode) }
    var remoteLock by remember { mutableStateOf(clientConfig.remoteMaintenanceLock) }
    var bannerText by remember { mutableStateOf(clientConfig.announcementBanner) }
    var cloudUrl by remember { mutableStateOf(storage.getCloudSyncUrl()) }
    var newAdminPwd by remember { mutableStateOf("") }

    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) onPickBackground(uri) }
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) onPickLogo(uri) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 84.dp)) {
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = cardColor, border = BorderStroke(1.dp, WolfGold.copy(alpha = 0.4f))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr(lang, "تىل ۋە كېچە-كۈندۈزلۈك كۆرۈنۈش", "اللغة والوضع الليلي/النهاري"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = textColor)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        AdminLanguage.entries.forEach { l ->
                            FilterChip(selected = lang == l, onClick = { onLangChange(l) }, label = { Text(l.title) }, modifier = Modifier.weight(1f))
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(lang, "كېچىلىك قاراڭغۇ ھالەت", "الوضع الليلي الداكن"), color = textColor, fontWeight = FontWeight.Bold)
                        Switch(checked = isDark, onCheckedChange = onDarkChange)
                    }
                    Button(onClick = { bgPicker.launch("image/*") }, colors = ButtonDefaults.buttonColors(containerColor = AdminIndigo), modifier = Modifier.fillMaxWidth()) {
                        Text(tr(lang, "تەگلىك رەسىم تاللاش", "اختيار صورة خلفية"), color = Color.White)
                    }
                    OutlinedButton(onClick = onResetBackground, modifier = Modifier.fillMaxWidth()) {
                        Text(tr(lang, "ئەسلى تەگلىككە قايتۇرۇش", "استعادة الخلفية الأصلية"))
                    }
                    Button(onClick = { logoPicker.launch("image/*") }, colors = ButtonDefaults.buttonColors(containerColor = WolfGold), modifier = Modifier.fillMaxWidth()) {
                        Text(tr(lang, "لوگو رەسىم تاللاش", "اختيار صورة الشعار"), color = WolfBlack, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }

        item {
            Surface(shape = RoundedCornerShape(22.dp), color = cardColor, border = BorderStroke(1.2.dp, AdminEmerald.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr(lang, "ھېسابات ئەپىنى باشقۇرۇش تەڭشەكلىرى", "إعدادات التحكم بتطبيق الحسابات"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = textColor)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(lang, "يېڭى ھېسابات تىزىملىتىشقا رۇخسەت", "السماح بالتسجيل الجديد"), color = textColor)
                        Switch(checked = allowReg, onCheckedChange = { allowReg = it })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(lang, "دەلىللەش كودى مەجبۇرىي بولسۇن", "إلزامية رمز التفعيل"), color = textColor)
                        Switch(checked = reqCode, onCheckedChange = { reqCode = it })
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(lang, "ھېسابات ئەپىنى يىراقتىن قۇلۇپلاش", "قفل تطبيق الحسابات عن بُعد"), color = AdminRose, fontWeight = FontWeight.Bold)
                        Switch(checked = remoteLock, onCheckedChange = { remoteLock = it })
                    }
                    OutlinedTextField(value = bannerText, onValueChange = { bannerText = it }, label = { Text(tr(lang, "مۇقىم ئېلان خېتى", "رسالة الإعلان")) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = cloudUrl, onValueChange = { cloudUrl = it; storage.setCloudSyncUrl(it) }, label = { Text(tr(lang, "قوشۇمچە بۇلۇت ئادرېسى (ئىختىيارىي)", "رابط سحابة إضافي (اختياري)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onSaveClientConfig(ClientAppControlConfig(allowReg, reqCode, remoteLock, bannerText)) }, colors = ButtonDefaults.buttonColors(containerColor = AdminEmerald), modifier = Modifier.fillMaxWidth()) {
                        Text(tr(lang, "تەڭشەكلەرنى ساقلاش ۋە يوللاش", "حفظ وإرسال الإعدادات"), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Surface(shape = RoundedCornerShape(22.dp), color = cardColor, border = BorderStroke(1.dp, AdminRose.copy(alpha = 0.4f))) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = newAdminPwd, onValueChange = { newAdminPwd = it }, label = { Text(tr(lang, "يېڭى باشقۇرغۇچى مەخپىي نومۇرى", "كلمة مرور المسؤول الجديدة")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            if (storage.updateAdminPassword(newAdminPwd)) {
                                newAdminPwd = ""
                                onShowNotice(tr(lang, "مەخپىي نومۇر يېڭىلاندى!", "تم تحديث كلمة المرور!"))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WolfGold),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(tr(lang, "مەخپىي نومۇرنى يېڭىلاش", "تحديث كلمة المرور"), color = WolfBlack, fontWeight = FontWeight.ExtraBold)
                    }
                    Button(onClick = onClearAllAccounts, colors = ButtonDefaults.buttonColors(containerColor = AdminRose), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(tr(lang, "بارلىق ھېساباتلارنى ئۆچۈرۈپ تازىلاش", "مسح جميع الحسابات"), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun HomeFilteredAccountsPage(
    lang: AdminLanguage,
    filter: HomeAccountListFilter,
    accounts: List<ManagedUserAccount>,
    searchQuery: String,
    cardColor: Color,
    textColor: Color,
    onSearchChange: (String) -> Unit,
    onBack: () -> Unit,
    onCopyCode: (String) -> Unit,
    onSendGmail: (ManagedUserAccount) -> Unit,
    onUpdateVipDays: (ManagedUserAccount, Int) -> Unit,
    onDelete: (ManagedUserAccount) -> Unit
) {
    val q = searchQuery.trim().lowercase()
    val list = remember(accounts, filter, q) {
        val base = if (filter == HomeAccountListFilter.VIP_MEMBERS) accounts.filter { it.isVipActive() } else accounts
        if (q.isEmpty()) base else base.filter {
            it.fullName.lowercase().contains(q) || it.username.lowercase().contains(q) || it.gmail.lowercase().contains(q)
        }
    }

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Surface(shape = RoundedCornerShape(22.dp), color = cardColor, border = BorderStroke(1.2.dp, WolfGold)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = WolfGold), shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = WolfBlack)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(tr(lang, "قايتىش", "رجوع"), color = WolfBlack, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = if (filter == HomeAccountListFilter.VIP_MEMBERS) tr(lang, "VIP ئەزالار (${list.size})", "أعضاء VIP (${list.size})")
                                else tr(lang, "ھېسابات ئاچقانلار (${list.size})", "الحسابات المفتوحة (${list.size})"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = textColor
                            )
                        }
                        OutlinedTextField(value = searchQuery, onValueChange = onSearchChange, placeholder = { Text(tr(lang, "ئىسىم ياكى Gmail ئىزدەش...", "بحث بالاسم أو Gmail...")) }, leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = WolfGold) }, singleLine = true, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            items(list, key = { it.id }) { acc ->
                Surface(shape = RoundedCornerShape(20.dp), color = cardColor, border = BorderStroke(1.dp, if (acc.isVipActive()) WolfGold else AdminTurquoise.copy(alpha = 0.4f))) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(acc.fullName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = textColor)
                                Text("Gmail: ${acc.gmail}", style = MaterialTheme.typography.bodySmall, color = AdminTurquoise)
                                Text("${tr(lang, "ھېسابات نامى", "المعرف")}: ${acc.username} | ${tr(lang, "مەخپىي نومۇر", "كلمة المرور")}: ${acc.password}", style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.8f))
                            }
                            IconButton(onClick = { onDelete(acc) }) {
                                Icon(Icons.Filled.Delete, contentDescription = null, tint = AdminRose)
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(7 to "VIP 7 كۈن", 30 to "VIP 1 ئاي", 365 to "VIP 1 يىل", 0 to "نورمال").forEach { (d, lbl) ->
                                FilterChip(selected = (d == 0 && !acc.isVipActive()), onClick = { onUpdateVipDays(acc, d) }, label = { Text(lbl) })
                            }
                        }

                        Surface(shape = RoundedCornerShape(12.dp), color = AdminEmerald.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(acc.licenseCode, fontWeight = FontWeight.ExtraBold, color = AdminEmerald)
                                Row {
                                    IconButton(onClick = { onCopyCode(acc.licenseCode) }) { Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = WolfGold) }
                                    IconButton(onClick = { onSendGmail(acc) }) { Icon(Icons.Filled.Email, contentDescription = null, tint = AdminTurquoise) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
