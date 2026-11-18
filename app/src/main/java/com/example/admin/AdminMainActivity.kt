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

fun tr(lang: AdminLanguage, ug: String, ar: String): String {
    return if (lang == AdminLanguage.UG) ug else ar
}

enum class AccountOpenMethod(val ugLabel: String, val arLabel: String, val icon: ImageVector) {
    BY_GMAIL("Gmail ئارقىلىق ھېسابات ئېچىش", "فتح حساب عبر Gmail", Icons.Filled.Email),
    BY_NAME("ئىسىم ئارقىلىق ھېسابات ئېچىش", "فتح حساب عبر الاسم", Icons.Filled.Person),
    BY_ACCOUNT_ID("ھېسابات نامى ئارقىلىق ئېچىش", "فتح حساب عبر المعرف", Icons.Filled.Badge)
}

class AdminMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        window.decorView.filterTouchesWhenObscured = true

        enableEdgeToEdge()
        setContent {
            AdminRootScreen()
        }
    }
}

data class ManagedUserAccount(
    val id: String,
    val openMethod: String,
    val fullName: String,
    val username: String,
    val gmail: String,
    val password: String,
    val address: String,
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
    private val prefs = context.getSharedPreferences("hesabat_admin_v4_vault", Context.MODE_PRIVATE)
    private val dbFile: File
        get() = File(context.filesDir, "hesabat_admin_v4_accounts.json")
    private val bgFile: File
        get() = File(context.filesDir, "admin_custom_bg.jpg")
    private val logoFile: File
        get() = File(context.filesDir, "admin_custom_logo.jpg")
    val hostedApkFile: File
        get() = File(context.filesDir, "direct_pushed_client.apk")

    private var localApkServer: ServerSocket? = null

    init {
        if (!prefs.getBoolean("cleaned_sample_accounts_v4", false)) {
            prefs.edit()
                .remove("accounts_json")
                .putBoolean("cleaned_sample_accounts_v4", true)
                .commit()
            runCatching { if (dbFile.exists()) dbFile.delete() }
        }
    }

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

    fun getLanguage(): AdminLanguage {
        return if (prefs.getString("lang", "ug") == "ar") AdminLanguage.AR else AdminLanguage.UG
    }

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
        val raw = runCatching {
            if (dbFile.exists()) dbFile.readText(Charsets.UTF_8) else ""
        }.getOrDefault("").ifBlank {
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
                    address = o.optString("address"),
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
                    put("address", a.address)
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

    fun loadLatestRelease(): AppVersionRelease {
        return AppVersionRelease(
            versionName = prefs.getString("rel_version", "1.1.0") ?: "1.1.0",
            apkDownloadUrl = prefs.getString("rel_url", "") ?: "",
            releaseNotes = prefs.getString("rel_notes", "") ?: "",
            forceUpdate = prefs.getBoolean("rel_force", false),
            targetAccount = prefs.getString("rel_target", "ALL") ?: "ALL",
            publishedAt = prefs.getString("rel_date", "") ?: ""
        )
    }

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

    fun loadClientControlConfig(): ClientAppControlConfig {
        return ClientAppControlConfig(
            allowNewRegistration = prefs.getBoolean("cfg_allow_reg", true),
            requireVerificationCode = prefs.getBoolean("cfg_req_code", true),
            remoteMaintenanceLock = prefs.getBoolean("cfg_remote_lock", false),
            announcementBanner = prefs.getString("cfg_banner", "") ?: ""
        )
    }

    fun saveClientControlConfig(cfg: ClientAppControlConfig) {
        prefs.edit()
            .putBoolean("cfg_allow_reg", cfg.allowNewRegistration)
            .putBoolean("cfg_req_code", cfg.requireVerificationCode)
            .putBoolean("cfg_remote_lock", cfg.remoteMaintenanceLock)
            .putString("cfg_banner", cfg.announcementBanner)
            .commit()
    }

    fun saveBackgroundFromUri(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                bgFile.outputStream().use { out -> input.copyTo(out) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clearBackground() {
        runCatching { if (bgFile.exists()) bgFile.delete() }
    }

    fun loadBackgroundBitmap(): ImageBitmap? {
        return try {
            if (bgFile.exists()) BitmapFactory.decodeFile(bgFile.absolutePath)?.asImageBitmap() else null
        } catch (_: Exception) {
            null
        }
    }

    fun saveLogoFromUri(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                logoFile.outputStream().use { out -> input.copyTo(out) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun loadLogoBitmap(): ImageBitmap? {
        return try {
            if (logoFile.exists()) BitmapFactory.decodeFile(logoFile.absolutePath)?.asImageBitmap() else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * تېلېفوندىن تاللانغان APK ھۆججىتىنى ۋاسىتىسىز بىۋاسىتە بۇلۇت ئوقىغا ۋە ئىچكى مۇلازىمېتىرغا چىقىرىش
     */
    suspend fun hostAndPushApkDirectly(
        apkUri: Uri,
        versionName: String,
        releaseNotes: String,
        forceUpdate: Boolean,
        targetAccount: String,
        onStatus: (String) -> Unit
    ): Result<AppVersionRelease> = withContext(Dispatchers.IO) {
        runCatching {
            withContext(Dispatchers.Main) {
                onStatus("APK ھۆججىتى تەييارلىنىۋاتىدۇ...")
            }
            context.contentResolver.openInputStream(apkUri)?.use { input ->
                hostedApkFile.outputStream().use { out ->
                    input.copyTo(out)
                }
            } ?: throw IllegalArgumentException("APK ھۆججىتىنى ئوقۇغىلى بولمىدى")

            // 1. ئىچكى Wi-Fi/Hotspot بىۋاسىتە تارقىتىش قانىلىنى قوزغىتىش
            startEmbeddedDirectApkServer()
            val localIp = getDeviceLocalIpAddress()
            var directApkUrl = if (localIp.isNotBlank()) "http://$localIp:8989/HesabatClient.apk" else ""

            // 2. يىراقتىكى تور ئارقىلىق بىۋاسىتە چۈشۈرۈش قانىلىغا ئاپتوماتىك يۈكلەش
            withContext(Dispatchers.Main) {
                onStatus("APK ھۆججىتى بىۋاسىتە يوللاش قانىلىغا چىقىرىلىۋاتىدۇ...")
            }
            val cloudUploadedUrl = uploadApkToDirectRelay(hostedApkFile)
            if (cloudUploadedUrl.isNotBlank()) {
                directApkUrl = cloudUploadedUrl
            }

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

    private fun uploadApkToDirectRelay(apkFile: File): String {
        return try {
            val boundary = "----HesabatDirectPush${System.currentTimeMillis()}"
            val url = URL("https://litterbox.catbox.moe/resources/internals/api.php")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            DataOutputStream(conn.outputStream).use { out ->
                // reqtype=fileupload
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"reqtype\"\r\n\r\n")
                out.writeBytes("fileupload\r\n")

                // time=72h
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"time\"\r\n\r\n")
                out.writeBytes("72h\r\n")

                // fileToUpload
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"fileToUpload\"; filename=\"HesabatClient.apk\"\r\n")
                out.writeBytes("Content-Type: application/vnd.android.package-archive\r\n\r\n")

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
                                    val len = hostedApkFile.length()
                                    out.writeBytes("HTTP/1.1 200 OK\r\n")
                                    out.writeBytes("Content-Type: application/vnd.android.package-archive\r\n")
                                    out.writeBytes("Content-Length: $len\r\n")
                                    out.writeBytes("Connection: close\r\n\r\n")
                                    hostedApkFile.inputStream().use { fileIn ->
                                        val buf = ByteArray(16384)
                                        while (true) {
                                            val r = fileIn.read(buf)
                                            if (r <= 0) break
                                            out.write(buf, 0, r)
                                        }
                                    }
                                    out.flush()
                                }
                            }
                            client.close()
                        }
                    }.start()
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun getDeviceLocalIpAddress(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    fun buildSyncPayloadJson(): String {
        val rel = loadLatestRelease()
        val cfg = loadClientControlConfig()
        val accountsArr = JSONArray(prefs.getString("accounts_json", "[]") ?: "[]")
        return JSONObject().apply {
            put("adminMasterEmail", ADMIN_MASTER_EMAIL)
            put("syncedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("accounts", accountsArr)
            put(
                "appUpdate",
                JSONObject().apply {
                    put("versionName", rel.versionName)
                    put("apkDownloadUrl", rel.apkDownloadUrl)
                    put("releaseNotes", rel.releaseNotes)
                    put("forceUpdate", rel.forceUpdate)
                    put("targetAccount", rel.targetAccount)
                    put("publishedAt", rel.publishedAt)
                }
            )
            put(
                "clientConfig",
                JSONObject().apply {
                    put("allowNewRegistration", cfg.allowNewRegistration)
                    put("requireVerificationCode", cfg.requireVerificationCode)
                    put("remoteMaintenanceLock", cfg.remoteMaintenanceLock)
                    put("announcementBanner", cfg.announcementBanner)
                }
            )
        }.toString(2)
    }

    suspend fun pushSyncToCloud(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint = getCloudSyncUrl().trim()
            if (endpoint.isEmpty()) {
                return@runCatching "LOCAL_SAVED"
            }
            val targetUrl = if (endpoint.endsWith(".json")) endpoint else "$endpoint/hesabat_sync.json"
            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { out ->
                out.write(buildSyncPayloadJson().toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            if (code in 200..299) "CLOUD_OK" else "HTTP_$code"
        }
    }

    fun clearAllData() {
        prefs.edit().remove("accounts_json").commit()
        runCatching { if (dbFile.exists()) dbFile.delete() }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

enum class AdminTab(val ugTitle: String, val arTitle: String, val icon: ImageVector) {
    HOME("باش بەت", "الرئيسية", Icons.Filled.Home),
    OPEN_ACCOUNT("ھېسابات ئېچىش", "فتح حساب", Icons.Filled.PersonAdd),
    VIP_CENTER("VIP ئەزالار", "أعضاء VIP", Icons.Filled.WorkspacePremium),
    UPDATE_PUSH("نەشر يوللاش", "تحديث التطبيق", Icons.Filled.SystemUpdate),
    SETTINGS("تەڭشەكلەر", "الإعدادات", Icons.Filled.Settings)
}

enum class HomeAccountListFilter {
    NONE,
    VIP_MEMBERS,
    ALL_OPENED_ACCOUNTS
}

@Composable
fun AdminBrandLogo(
    customLogo: ImageBitmap?,
    sizeDp: Int = 72
) {
    if (customLogo != null) {
        Image(
            bitmap = customLogo,
            contentDescription = "Admin Logo",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape((sizeDp / 3).dp))
                .border(2.dp, WolfGold, RoundedCornerShape((sizeDp / 3).dp))
        )
    } else {
        Image(
            painter = painterResource(id = R.drawable.ic_admin_logo),
            contentDescription = "Wolf Admin Logo",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape((sizeDp / 3).dp))
                .border(2.dp, WolfGold, RoundedCornerShape((sizeDp / 3).dp))
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

    val sendViaMasterGmail: (String, String, String) -> Unit = { targetGmail, subject, body ->
        runCatching {
            val recipient = targetGmail.trim().ifBlank { ADMIN_MASTER_EMAIL }
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$recipient")
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient, ADMIN_MASTER_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            context.startActivity(Intent.createChooser(intent, tr(lang, "Gmail ئارقىلىق يوللاش", "إرسال عبر Gmail")))
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = if (isDark) {
                darkColorScheme(
                    primary = WolfGold,
                    secondary = AdminTurquoise,
                    background = bgColor,
                    surface = cardColor,
                    onSurface = textColor,
                    onBackground = textColor
                )
            } else {
                lightColorScheme(
                    primary = AdminIndigo,
                    secondary = AdminTurquoise,
                    background = bgColor,
                    surface = cardColor,
                    onSurface = textColor,
                    onBackground = textColor
                )
            }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (customBg != null) {
                    Image(
                        bitmap = customBg!!,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(if (isDark) Color(0xAA050914) else Color(0x88FFFFFF))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
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
                                scope.launch {
                                    snackbarHostState.showSnackbar(tr(lang, "كود كۆچۈرۈلدى: $code", "تم نسخ الرمز: $code"))
                                }
                            },
                            onSendGmail = { acc ->
                                sendViaMasterGmail(
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
                            onDelete = { acc ->
                                saveAndSyncAccounts(accounts.filterNot { it.id == acc.id })
                            }
                        )
                    } else {
                        Scaffold(
                            containerColor = Color.Transparent,
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                            bottomBar = {
                                NavigationBar(
                                    containerColor = if (isDark) Color(0xF00B1120) else Color(0xF0FFFFFF),
                                    tonalElevation = 10.dp
                                ) {
                                    AdminTab.entries.forEach { tab ->
                                        NavigationBarItem(
                                            selected = currentTab == tab,
                                            onClick = { currentTab = tab },
                                            icon = { Icon(tab.icon, contentDescription = null) },
                                            label = {
                                                Text(
                                                    text = tr(lang, tab.ugTitle, tab.arTitle),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = if (currentTab == tab) FontWeight.ExtraBold else FontWeight.Normal
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        ) { innerPadding ->
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .padding(horizontal = 16.dp)
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 10.dp, bottom = 10.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    color = cardColor,
                                    border = BorderStroke(1.2.dp, WolfGold.copy(alpha = 0.55f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            AdminBrandLogo(customLogo = customLogo, sizeDp = 46)
                                            Column {
                                                Text(
                                                    text = tr(lang, "باش باشقۇرغۇچى سۇپىسى", "لوحة تحكم المسؤول"),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = textColor
                                                )
                                                Text(
                                                    text = ADMIN_MASTER_EMAIL,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = WolfGold,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    isDark = !isDark
                                                    storage.setDarkMode(isDark)
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                                    contentDescription = null,
                                                    tint = WolfGold
                                                )
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
                                            onTriggerSearch = {
                                                homeFilterOverlay = HomeAccountListFilter.ALL_OPENED_ACCOUNTS
                                            },
                                            onOpenVipMembers = {
                                                searchQuery = ""
                                                homeFilterOverlay = HomeAccountListFilter.VIP_MEMBERS
                                            },
                                            onOpenAllAccounts = {
                                                searchQuery = ""
                                                homeFilterOverlay = HomeAccountListFilter.ALL_OPENED_ACCOUNTS
                                            },
                                            onNavigateTab = { currentTab = it }
                                        )
                                    }

                                    AdminTab.OPEN_ACCOUNT -> {
                                        AdminOpenAccountTab(
                                            lang = lang,
                                            storage = storage,
                                            accounts = accounts,
                                            cardColor = cardColor,
                                            textColor = textColor,
                                            onAccountCreated = { newAcc, sendEmailNow ->
                                                saveAndSyncAccounts(listOf(newAcc) + accounts)
                                                clipboard.setText(AnnotatedString(newAcc.licenseCode))
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        tr(
                                                            lang,
                                                            "ھېسابات ئېچىلدى ۋە كودى كۆچۈرۈلدى: ${newAcc.licenseCode}",
                                                            "تم فتح الحساب ونسخ الرمز: ${newAcc.licenseCode}"
                                                        )
                                                    )
                                                }
                                                if (sendEmailNow) {
                                                    sendViaMasterGmail(
                                                        newAcc.gmail,
                                                        tr(lang, "Hesabat يېڭى ھېسابات ۋە دەلىللەش كودىڭىز", "حساب Hesabat الجديد ورمز التفعيل"),
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

                                    AdminTab.VIP_CENTER -> {
                                        AdminVipDurationTab(
                                            lang = lang,
                                            accounts = accounts,
                                            storage = storage,
                                            cardColor = cardColor,
                                            textColor = textColor,
                                            onUpdateVip = { targetAcc, days ->
                                                val isVipNow = days > 0
                                                val expireMillis = if (isVipNow) System.currentTimeMillis() + days.toLong() * 24L * 3600L * 1000L else 0L
                                                val updatedCode = storage.generateVerificationCode(targetAcc.username, isVipNow)
                                                saveAndSyncAccounts(
                                                    accounts.map {
                                                        if (it.id == targetAcc.id) {
                                                            it.copy(
                                                                isVip = isVipNow,
                                                                vipExpireEpochMillis = expireMillis,
                                                                status = if (isVipNow) "VIP" else "ACTIVE",
                                                                licenseCode = updatedCode
                                                            )
                                                        } else it
                                                    }
                                                )
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        if (isVipNow) tr(lang, "VIP مۇددىتى بېكىتىلدى ($days كۈن)", "تم تحديد مدة VIP ($days يوم)")
                                                        else tr(lang, "VIP ھوقۇقى توختىتىلدى", "تم إيقاف VIP")
                                                    )
                                                }
                                            }
                                        )
                                    }

                                    AdminTab.UPDATE_PUSH -> {
                                        // ۋاسىتىسىز بىۋاسىتە APK تاللاپ يوللايدىغان نەشر يوللاش رامكىسى
                                        AdminDirectApkPushTab(
                                            lang = lang,
                                            storage = storage,
                                            latestRelease = latestRelease,
                                            cardColor = cardColor,
                                            textColor = textColor,
                                            onReleaseUpdated = { updatedRel ->
                                                latestRelease = updatedRel
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        tr(
                                                            lang,
                                                            "يېڭى نەشر (${updatedRel.versionName}) ۋاسىتىسىز بىۋاسىتە ھېسابات ئەپىگە يوللاندى!",
                                                            "تم إرسال الإصدار الجديد (${updatedRel.versionName}) مباشرة إلى تطبيق الحسابات!"
                                                        )
                                                    )
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
                                            onLangChange = { newLang ->
                                                lang = newLang
                                                storage.setLanguage(newLang)
                                            },
                                            onDarkChange = { dark ->
                                                isDark = dark
                                                storage.setDarkMode(dark)
                                            },
                                            onPickBackground = { uri ->
                                                if (storage.saveBackgroundFromUri(uri)) {
                                                    customBg = storage.loadBackgroundBitmap()
                                                }
                                            },
                                            onResetBackground = {
                                                storage.clearBackground()
                                                customBg = null
                                            },
                                            onPickLogo = { uri ->
                                                if (storage.saveLogoFromUri(uri)) {
                                                    customLogo = storage.loadLogoBitmap()
                                                }
                                            },
                                            onSaveClientConfig = { newCfg ->
                                                clientConfig = newCfg
                                                storage.saveClientControlConfig(newCfg)
                                                scope.launch {
                                                    storage.pushSyncToCloud()
                                                    snackbarHostState.showSnackbar(
                                                        tr(lang, "ھېسابات ئەپى تەڭشەكلىرى بىۋاسىتە يوللاندى!", "تم إرسال إعدادات تطبيق الحسابات مباشرة!")
                                                    )
                                                }
                                            },
                                            onClearAllAccounts = {
                                                storage.clearAllData()
                                                accounts = emptyList()
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        tr(lang, "بارلىق ھېساباتلار پاكىز تازىلاندى!", "تم مسح جميع الحسابات!")
                                                    )
                                                }
                                            },
                                            onShowNotice = { msg ->
                                                scope.launch { snackbarHostState.showSnackbar(msg) }
                                            }
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

/**
 * 4-بۆلۈم: ۋاسىتىسىز بىۋاسىتە APK ھۆججىتىنى تاللاپ ھېسابات ئەپلىرىگە يوللاش رامكىسى
 * (Gmail ياكى باشقا ئەپ ئاچمايدۇ، ئۇلانما كۆچۈرۈپ يۈرمەيسىز، بىۋاسىتە مۇشۇ رامكىدىلا APK تاللاپ يوللايسىز)
 */
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
    var selectedApkSizeMb by remember { mutableStateOf("") }

    var isPushingNow by remember { mutableStateOf(false) }
    var pushStatusText by remember { mutableStateOf("") }

    val apkFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            selectedApkUri = uri
            runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) selectedApkName = cursor.getString(nameIdx) ?: "HesabatClient.apk"
                        if (sizeIdx >= 0) {
                            val bytes = cursor.getLong(sizeIdx)
                            selectedApkSizeMb = String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f))
                        }
                    }
                }
            }
            if (selectedApkName.isBlank()) selectedApkName = "HesabatClient.apk"
        }
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 84.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = cardColor,
                border = BorderStroke(1.8.dp, WolfGold)
            ) {
                Column(modifier = Modifier.padding(18.dp),VerticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = WolfGold, modifier = Modifier.size(30.dp))
                        Column {
                            Text(
                                text = tr(lang, "ۋاسىتىسىز بىۋاسىتە نەشر يوللاش مەركىزى", "مركز إرسال التحديث المباشر للتطبيقات"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = textColor
                            )
                            Text(
                                text = tr(
                                    lang,
                                    "تېلېفونىڭىزدىكى APK ھۆججىتىنى تاللاپ بىۋاسىتە مۇشۇ رامكىدىلا ھېسابات ئەپىگە يوللايسىز",
                                    "اختر ملف APK من هاتفك وأرسله مباشرة من هذه النافذة إلى تطبيق الحسابات"
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = WolfGold
                            )
                        }
                    }

                    // 1. تېلېفوندىن APK ھۆججىتىنى تاللاش كۇنۇپكىسى
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (selectedApkUri != null) AdminEmerald.copy(alpha = 0.16f) else WolfGold.copy(alpha = 0.14f),
                        border = BorderStroke(1.5.dp, if (selectedApkUri != null) AdminEmerald else WolfGold),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { apkFilePicker.launch("*/*") }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (selectedApkUri != null) Icons.Filled.CheckCircle else Icons.Filled.UploadFile,
                                    contentDescription = null,
                                    tint = if (selectedApkUri != null) AdminEmerald else WolfGold,
                                    modifier = Modifier.size(28.dp)
                                )
                                Column {
                                    Text(
                                        text = if (selectedApkUri != null) selectedApkName
                                        else tr(lang, "تېلېفوندىن يېڭى APK ھۆججىتىنى تاللاڭ *", "اختر ملف APK الجديد من الهاتف *"),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = textColor
                                    )
                                    Text(
                                        text = if (selectedApkUri != null) {
                                            "${tr(lang, "ھەجىمى", "الحجم")}: $selectedApkSizeMb • ${tr(lang, "يوللاشقا تەييار", "جاهز للإرسال المباشر")}"
                                        } else {
                                            tr(lang, "بۇ يەرنى چېكىپ چۈشۈرگەن APK ھۆججىتىنى تاللاڭ", "اضغط هنا لاختيار ملف APK من ملفات هاتفك")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selectedApkUri != null) AdminEmerald else textColor.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            Text(
                                text = tr(lang, "تاللاش", "اختيار"),
                                fontWeight = FontWeight.ExtraBold,
                                color = WolfGold
                            )
                        }
                    }

                    OutlinedTextField(
                        value = versionName,
                        onValueChange = { versionName = it },
                        label = { Text(tr(lang, "يېڭى نەشر نومۇرى (مەسىلەن: 2.0.0) *", "رقم الإصدار الجديد (مثال: 2.0.0) *")) },
                        leadingIcon = { Icon(Icons.Filled.Verified, contentDescription = null, tint = WolfGold) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text(tr(lang, "يېڭى نەشر چۈشەندۈرۈشى", "ملاحظات الإصدار الجديد")) },
                        placeholder = { Text(tr(lang, "يېڭى ئىقتىدارلار قوشۇلدى...", "تم إضافة ميزات جديدة...")) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = targetAccount,
                        onValueChange = { targetAccount = it },
                        label = { Text(tr(lang, "يوللىنىدىغان ھېسابات (ALL = بارلىق ھېسابات ئەپلىرىگە)", "الحساب المستهدف (ALL = جميع الحسابات)")) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = tr(lang, "مەجبۇرىي يېڭىلاش (ھېسابات ئەپىدە دەرھال چىقىدۇ)", "تحديث إجباري فوري في تطبيق الحسابات"),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = textColor
                        )
                        Switch(checked = forceUpdate, onCheckedChange = { forceUpdate = it })
                    }

                    if (pushStatusText.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = AdminTurquoise.copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = pushStatusText,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = AdminTurquoise,
                                modifier = Modifier.padding(12.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // 2. ۋاسىتىسىز بىۋاسىتە ھېسابات ئەپىگە يوللاش كۇنۇپكىسى
                    Button(
                        onClick = {
                            if (selectedApkUri == null) {
                                pushStatusText = tr(lang, "ئالدى بىلەن ئۈستىدىكى رامكىنى چېكىپ APK ھۆججىتىنى تاللاڭ!", "يرجى اختيار ملف APK أولاً من المربع أعلاه!")
                                return@Button
                            }
                            if (!isPushingNow) {
                                scope.launch {
                                    isPushingNow = true
                                    val res = storage.hostAndPushApkDirectly(
                                        apkUri = selectedApkUri!!,
                                        versionName = versionName,
                                        releaseNotes = notes,
                                        forceUpdate = forceUpdate,
                                        targetAccount = targetAccount,
                                        onStatus = { status -> pushStatusText = status }
                                    )
                                    isPushingNow = false
                                    res.onSuccess { published ->
                                        pushStatusText = tr(
                                            lang,
                                            "✅ مۇۋەپپەقىيەتلىك يوللاندى! ھېسابات ئەپلىرى بۇ نەشرنى ئەپ ئىچىدىلا بىۋاسىتە چۈشۈرۈپ قاچىلايدۇ.",
                                            "✅ تم الإرسال المباشر بنجاح! ستقوم تطبيقات الحسابات بتحميل وتثبيت التحديث داخلياً."
                                        )
                                        onReleaseUpdated(published)
                                    }.onFailure { err ->
                                        pushStatusText = "خاتالىق: ${err.message}"
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = WolfGold),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                    ) {
                        if (isPushingNow) {
                            CircularProgressIndicator(color = WolfBlack, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = tr(lang, "ھېسابات ئەپىگە بىۋاسىتە يوللىنىۋاتىدۇ...", "جاري الإرسال المباشر لتطبيق الحسابات..."),
                                color = WolfBlack,
                                fontWeight = FontWeight.ExtraBold
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = WolfBlack)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = tr(lang, "ۋاسىتىسىز بىۋاسىتە ھېسابات ئەپىگە يوللاش", "إرسال التحديث مباشرة لتطبيق الحسابات"),
                                color = WolfBlack,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }
}
