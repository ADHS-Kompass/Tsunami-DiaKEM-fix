package app.aaps.plugins.source

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import app.aaps.plugins.source.activities.RequestDexcomPermissionActivity
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.same
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Base64

class DexcomPluginTest : TestBaseWithProfile() {

    private lateinit var dexcomPlugin: DexcomPlugin
    private lateinit var packageManager: PackageManager
    private val installedPackages = mutableMapOf<String, PackageInfo>()
    private var registeredPermission: PermissionInfo? = null
    private val packageInfoFlags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES

    @BeforeEach
    fun setup() {
        packageManager = mock()
        whenever(context.packageManager).thenReturn(packageManager)
        @Suppress("DEPRECATION")
        whenever(packageManager.getPackageInfo(any<String>(), eq(packageInfoFlags))).thenAnswer {
            val packageName = it.getArgument<String>(0)
            installedPackages[packageName] ?: throw PackageManager.NameNotFoundException(packageName)
        }
        whenever(packageManager.getPermissionInfo(eq(DexcomPlugin.PERMISSION), eq(0))).thenAnswer {
            registeredPermission ?: throw PackageManager.NameNotFoundException(DexcomPlugin.PERMISSION)
        }
        whenever(context.checkPermission(eq(DexcomPlugin.PERMISSION), any(), any())).thenReturn(PackageManager.PERMISSION_DENIED)
        dexcomPlugin = DexcomPlugin(rh, aapsLogger, context, config, preferences, mock())
    }

    private fun companion(
        packageName: String = "com.dexcom.g7",
        versionName: String? = "1.6.1.4537",
        versionCode: Long = 4537L,
        declaresPermission: Boolean = false,
        requestedPermissions: Array<String>? = arrayOf("android.permission.INTERNET"),
        signingInfo: SigningInfo? = signingInfo()
    ): PackageInfo = mock<PackageInfo>().also { info ->
        info.packageName = packageName
        info.versionName = versionName
        info.permissions = if (declaresPermission) arrayOf(PermissionInfo().also { it.name = DexcomPlugin.PERMISSION }) else null
        info.requestedPermissions = requestedPermissions
        info.signingInfo = signingInfo
        // Plain Android host stubs do not compute this from versionCode fields.
        whenever(info.longVersionCode).thenReturn(versionCode)
        installedPackages[packageName] = info
    }

    private fun signature(certificate: ByteArray? = diakemCertificate()): Signature = mock<Signature>().also {
        whenever(it.toByteArray()).thenReturn(certificate)
    }

    private fun signingInfo(
        currentSigners: Array<Signature>? = arrayOf(signature()),
        multipleSigners: Boolean = false
    ): SigningInfo = mock<SigningInfo>().also {
        whenever(it.apkContentsSigners).thenReturn(currentSigners)
        whenever(it.hasMultipleSigners()).thenReturn(multipleSigners)
    }

    private fun otherSignature(): Signature = signature(diakemCertificate().also {
        // A different certificate byte sequence must not match the known SHA-256 fingerprint.
        it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
    })

    private fun assertRequiredAndRequested() {
        val groups = dexcomPlugin.requiredPermissions()
        assertThat(groups).hasSize(1)
        assertThat(groups.single().permissions).containsExactly(DexcomPlugin.PERMISSION)
        assertThat(groups.single().special).isTrue()
        assertPermissionActivityRequested()
    }

    private fun assertPermissionActivityRequested() {
        // Host Android stubs do not retain Intent fields, so check construction and the exact mock sent.
        mockConstruction(Intent::class.java) { _, construction ->
            assertThat(construction.arguments()).containsExactly(context, RequestDexcomPermissionActivity::class.java).inOrder()
        }.use { intents ->
            dexcomPlugin.requestPermissionIfNeeded()
            assertThat(intents.constructed()).hasSize(1)
            val intent = intents.constructed().single()
            verify(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            verify(context).startActivity(same(intent))
        }
    }

    @Test
    fun `known permission-free G7 with actual DiaKEM current certificate has no permission group or startup request`() {
        companion()

        assertThat(dexcomPlugin.requiredPermissions()).isEmpty()
        dexcomPlugin.requestPermissionIfNeeded()

        verify(context, never()).startActivity(any())
    }

    @Test
    fun `same G7 version with a different current certificate keeps the permission required`() {
        companion(signingInfo = signingInfo(arrayOf(otherSignature())))

        assertRequiredAndRequested()
    }

    @Test
    fun `missing signing info keeps the permission required`() {
        companion(signingInfo = null)

        assertRequiredAndRequested()
    }

    @Test
    fun `missing current signers keep the permission required`() {
        companion(signingInfo = signingInfo(null))

        assertRequiredAndRequested()
    }

    @Test
    fun `empty current signers keep the permission required`() {
        companion(signingInfo = signingInfo(emptyArray()))

        assertRequiredAndRequested()
    }

    @Test
    fun `null current signer entry keeps the permission required`() {
        val signingInfo = signingInfo()
        doReturn(arrayOfNulls<Signature>(1)).whenever(signingInfo).apkContentsSigners
        companion(signingInfo = signingInfo)

        assertRequiredAndRequested()
    }

    @Test
    fun `multiple current signers including DiaKEM keep the permission required`() {
        companion(signingInfo = signingInfo(arrayOf(signature(), otherSignature()), multipleSigners = true))

        assertRequiredAndRequested()
    }

    @Test
    fun `multiple signer flag with one current signer keeps the permission required`() {
        companion(signingInfo = signingInfo(multipleSigners = true))

        assertRequiredAndRequested()
    }

    @Test
    fun `single signer flag with multiple current signers keeps the permission required`() {
        companion(signingInfo = signingInfo(arrayOf(signature(), otherSignature())))

        assertRequiredAndRequested()
    }

    @Test
    fun `missing certificate bytes keep the permission required`() {
        companion(signingInfo = signingInfo(arrayOf(signature(null))))

        assertRequiredAndRequested()
    }

    @Test
    fun `empty certificate bytes keep the permission required`() {
        companion(signingInfo = signingInfo(arrayOf(signature(byteArrayOf()))))

        assertRequiredAndRequested()
    }

    @Test
    fun `signer query failure keeps the permission required`() {
        val signingInfo = signingInfo()
        whenever(signingInfo.apkContentsSigners).thenThrow(SecurityException("Signing query failed"))
        companion(signingInfo = signingInfo)

        assertRequiredAndRequested()
    }

    @Test
    fun `certificate read failure keeps the permission required`() {
        val signer = signature()
        whenever(signer.toByteArray()).thenThrow(IllegalStateException("Certificate read failed"))
        companion(signingInfo = signingInfo(arrayOf(signer)))

        assertRequiredAndRequested()
    }

    @Test
    fun `DiaKEM certificate only in signing history does not exempt a different current signer`() {
        val currentSigner = otherSignature()
        val signingInfo = signingInfo(arrayOf(currentSigner))
        val history = arrayOf(signature(), currentSigner)
        whenever(signingInfo.hasPastSigningCertificates()).thenReturn(true)
        whenever(signingInfo.signingCertificateHistory).thenReturn(history)
        companion(signingInfo = signingInfo)

        assertRequiredAndRequested()
    }

    @Test
    fun `same G7 version declaring the permission still requires and requests it`() {
        companion(declaresPermission = true)

        assertRequiredAndRequested()
    }

    @Test
    fun `declared and granted permission stays listed without a startup request`() {
        companion(declaresPermission = true)
        whenever(context.checkPermission(eq(DexcomPlugin.PERMISSION), any(), any())).thenReturn(PackageManager.PERMISSION_GRANTED)

        assertThat(dexcomPlugin.requiredPermissions().flatMap { it.permissions }).containsExactly(DexcomPlugin.PERMISSION)
        dexcomPlugin.requestPermissionIfNeeded()

        verify(context, never()).startActivity(any())
    }

    @Test
    fun `requested permission without a declaration still requires and requests it`() {
        companion(requestedPermissions = arrayOf(DexcomPlugin.PERMISSION))

        assertRequiredAndRequested()
    }

    @Test
    fun `registered permission still requires and requests it regardless of owner`() {
        companion()
        registeredPermission = PermissionInfo().also {
            it.name = DexcomPlugin.PERMISSION
            it.packageName = "another.permission.owner"
        }

        assertRequiredAndRequested()
    }

    @Test
    fun `registered and granted permission stays listed without a startup request`() {
        companion()
        registeredPermission = PermissionInfo().also { it.name = DexcomPlugin.PERMISSION }
        whenever(context.checkPermission(eq(DexcomPlugin.PERMISSION), any(), any())).thenReturn(PackageManager.PERMISSION_GRANTED)

        assertThat(dexcomPlugin.requiredPermissions().flatMap { it.permissions }).containsExactly(DexcomPlugin.PERMISSION)
        dexcomPlugin.requestPermissionIfNeeded()

        verify(context, never()).startActivity(any())
    }

    @Test
    fun `different G7 version name keeps the permission required`() {
        companion(versionName = "1.6.2.4537")

        assertRequiredAndRequested()
    }

    @Test
    fun `different G7 version code keeps the permission required`() {
        companion(versionCode = 4538L)

        assertRequiredAndRequested()
    }

    @Test
    fun `matching lower version code with a different upper version code keeps the permission required`() {
        companion(versionCode = (1L shl 32) or 4537L)

        assertRequiredAndRequested()
    }

    @Test
    fun `unknown G7 version keeps the permission required`() {
        companion(versionName = null)

        assertRequiredAndRequested()
    }

    @Test
    fun `missing G7 version code keeps the permission required`() {
        companion(versionCode = 0L)

        assertRequiredAndRequested()
    }

    @Test
    fun `missing requested-permission metadata keeps the permission required`() {
        companion(requestedPermissions = null)

        assertRequiredAndRequested()
    }

    @Test
    fun `empty requested-permission metadata keeps the permission required`() {
        companion(requestedPermissions = emptyArray())

        assertRequiredAndRequested()
    }

    @Test
    fun `incomplete declaration metadata keeps the permission required`() {
        companion().permissions = arrayOf(PermissionInfo())

        assertRequiredAndRequested()
    }

    @Test
    fun `G6 alongside known permission-free G7 keeps the permission required`() {
        companion()
        companion(packageName = "com.dexcom.g6")

        assertRequiredAndRequested()
    }

    @Test
    fun `each recognized G6 package keeps the permission required`() {
        for (packageName in dexcomPlugin.dexcomPackages().filter { it != "com.dexcom.g7" }) {
            installedPackages.clear()
            companion(packageName = packageName)
            assertThat(dexcomPlugin.requiredPermissions().flatMap { it.permissions }).containsExactly(DexcomPlugin.PERMISSION)
        }
    }

    @Test
    fun `unexpected permission query failure keeps the permission required`() {
        companion()
        doThrow(SecurityException("Permission query failed")).whenever(packageManager).getPermissionInfo(DexcomPlugin.PERMISSION, 0)

        assertRequiredAndRequested()
    }

    @Test
    fun `unexpected package query failure keeps the permission required`() {
        @Suppress("DEPRECATION")
        doThrow(IllegalStateException("Package query failed")).whenever(packageManager).getPackageInfo("com.dexcom.g7", packageInfoFlags)

        assertRequiredAndRequested()
    }

    @Test
    fun `null package metadata keeps the permission required`() {
        @Suppress("DEPRECATION")
        doReturn(null).whenever(packageManager).getPackageInfo("com.dexcom.g7", packageInfoFlags)

        assertRequiredAndRequested()
    }

    @Test
    fun `null package manager keeps the permission required`() {
        whenever(context.packageManager).thenReturn(null)

        assertRequiredAndRequested()
    }

    @Test
    fun `null permission query result keeps the permission required`() {
        companion()
        doReturn(null).whenever(packageManager).getPermissionInfo(DexcomPlugin.PERMISSION, 0)

        assertRequiredAndRequested()
    }

    @Test
    fun `mismatched package metadata keeps the permission required`() {
        companion().packageName = "different.package"

        assertRequiredAndRequested()
    }

    @Test
    fun `no companion leaves groups empty but keeps the denied startup request`() {
        registeredPermission = PermissionInfo().also { it.name = DexcomPlugin.PERMISSION }
        assertThat(dexcomPlugin.requiredPermissions()).isEmpty()

        assertPermissionActivityRequested()
    }

    @Test
    fun `no companion and granted permission does not request again`() {
        whenever(context.checkPermission(eq(DexcomPlugin.PERMISSION), any(), any())).thenReturn(PackageManager.PERMISSION_GRANTED)

        assertThat(dexcomPlugin.requiredPermissions()).isEmpty()
        dexcomPlugin.requestPermissionIfNeeded()

        verify(context, never()).startActivity(any())
    }

    @Test
    fun `permission registration is checked again on the same plugin`() {
        companion()
        assertThat(dexcomPlugin.requiredPermissions()).isEmpty()
        dexcomPlugin.requestPermissionIfNeeded()
        verify(context, never()).startActivity(any())

        registeredPermission = PermissionInfo().also { it.name = DexcomPlugin.PERMISSION }
        assertRequiredAndRequested()

        registeredPermission = null
        assertThat(dexcomPlugin.requiredPermissions()).isEmpty()
        dexcomPlugin.requestPermissionIfNeeded()
        verify(context, times(1)).startActivity(any())
    }

    @Test
    fun `companion installation and update are checked again on the same plugin`() {
        assertThat(dexcomPlugin.requiredPermissions()).isEmpty()
        companion()
        assertThat(dexcomPlugin.requiredPermissions()).isEmpty()
        dexcomPlugin.requestPermissionIfNeeded()
        verify(context, never()).startActivity(any())

        companion(declaresPermission = true)
        assertRequiredAndRequested()

        installedPackages.clear()
        assertThat(dexcomPlugin.requiredPermissions()).isEmpty()
        assertPermissionActivityRequested()
        verify(context, times(2)).startActivity(any())
    }

    @Test
    fun `current signer changes are checked again on the same plugin`() {
        val info = companion()
        assertThat(dexcomPlugin.requiredPermissions()).isEmpty()
        dexcomPlugin.requestPermissionIfNeeded()
        verify(context, never()).startActivity(any())

        info.signingInfo = signingInfo(arrayOf(otherSignature()))
        assertRequiredAndRequested()

        info.signingInfo = signingInfo()
        assertThat(dexcomPlugin.requiredPermissions()).isEmpty()
        dexcomPlugin.requestPermissionIfNeeded()
        verify(context, times(1)).startActivity(any())
    }

    // Public X.509 certificate from the supplied DiaKEM 1.6.1.4537 APK, not an APK hash or private key.
    // The production MessageDigest hashes these real DER bytes; the test does not stub the digest.
    private fun diakemCertificate(): ByteArray = Base64.getMimeDecoder().decode(
        """
        MIID0TCCArmgAwIBAgIIU8h8ryHzRAkwDQYJKoZIhvcNAQELBQAwgZUxCzAJBgNV
        BAYTAkRFMR8wHQYDVQQIExZOb3J0aCBSaGluZS1XZXN0cGhhbGlhMRMwEQYDVQQH
        EwpPYmVyaGF1c2VuMR8wHQYDVQQKExZEZXhjb20gRzcgSGFja2luZyBVbml0MRQw
        EgYDVQQLEwtEZXZlbG9wbWVudDEZMBcGA1UEAxMQRGV4Y29tIEc3IEhhY2tlcjAg
        Fw0yMjEwMTIxODM3NDNaGA8yMDUwMDIyNzE4Mzc0M1owgZUxCzAJBgNVBAYTAkRF
        MR8wHQYDVQQIExZOb3J0aCBSaGluZS1XZXN0cGhhbGlhMRMwEQYDVQQHEwpPYmVy
        aGF1c2VuMR8wHQYDVQQKExZEZXhjb20gRzcgSGFja2luZyBVbml0MRQwEgYDVQQL
        EwtEZXZlbG9wbWVudDEZMBcGA1UEAxMQRGV4Y29tIEc3IEhhY2tlcjCCASIwDQYJ
        KoZIhvcNAQEBBQADggEPADCCAQoCggEBAKXv0g7GpvSRjYV9qVqZciRGI0wA9E7h
        H8T3d4inxFsp3VyRuTStg5ybinYFdrxCoYSDtojVcQ28zpFUG9xDk/TsXm7tlSXQ
        sba55mf4FMoAhgnU2KcjzDuKL6JNVpWKTz/0MD1kMjKHavuXVLQnlcmsoe2H8IKc
        QWJZmcenhG/pHrFkSvnjpfgBbxPJ0rwGSiruiGh4pdI/EYXZXpjQS/1MaHNOcZTW
        R6HgWRyITYkfJpI2DEZJca9bQulP9JRDRxkJIxcZSwrWoqjWg8ByMHO2SaA0DcSf
        hVEAzvaTH6i7QNf8cd5Rw19z7rL900wv1dEuD/lfb7fkzcc9EaIn12ECAwEAAaMh
        MB8wHQYDVR0OBBYEFO7PtmQhwgAfaFVcKkzGZf1C9Qy0MA0GCSqGSIb3DQEBCwUA
        A4IBAQA7eYEkLdi7+BkGVAOsvJat0rmnrLEmlXYik9v1qjfzuSu9dLv+5o6lRvOz
        mZib37/OwoUYN8WGDkn4DWo87YmbXn9vYkptSnfixvfVNb6gBaiYaLXk+hXN7ViA
        cO4Xu6zLWfn9+XzpyeeykeLR/cbzcaI2A205san7VTsTmR9WB9ZMGcLOc/eLfA+B
        xLU9H9KYXfwMR06lUlKErerQh2E9kerOZa9L1swXANy2HE3Vp++6sTrmb/7zx/v5
        /WvLTmATwTgkALLDf4yt0av3EPdGIpuk+s08oI7XjXsBhRHJ/iyVdHXd+SK4mcyb
        rCw4iHXFyAYjBsneGWGD1kQBWUnJ
        """.trimIndent()
    )
}
