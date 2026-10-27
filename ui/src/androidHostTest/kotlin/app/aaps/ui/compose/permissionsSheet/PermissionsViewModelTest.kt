package app.aaps.ui.compose.permissionsSheet

import app.aaps.core.interfaces.plugin.PermissionGroup
import app.aaps.core.interfaces.plugin.PluginPermissions
import app.aaps.core.keys.interfaces.TextRef
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
internal class PermissionsViewModelTest {

    @Mock private lateinit var pluginPermissions: PluginPermissions

    private lateinit var sut: PermissionsViewModel

    private val dexcomGroup = PermissionGroup(
        permissions = listOf("com.dexcom.cgm.EXTERNAL_PERMISSION"),
        rationaleTitle = TextRef.Literal("Permission"),
        rationaleDescription = TextRef.Literal(""),
        special = true
    )
    private val otherGroup = PermissionGroup(
        permissions = listOf("android.permission.POST_NOTIFICATIONS"),
        rationaleTitle = TextRef.Literal("Permission"),
        rationaleDescription = TextRef.Literal("")
    )

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        // requestPermission()/onPermissionsDenied() use viewModelScope; setMain keeps those deferred.
        // Construction reads nothing from deps (no init block), so no stubbing is required to build.
        Dispatchers.setMain(StandardTestDispatcher())
        sut = PermissionsViewModel(pluginPermissions)
    }

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `default uiState has no items and sheet hidden`() {
        val state = sut.uiState.value
        assertThat(state.items).isEmpty()
        assertThat(state.hasAnyMissing).isFalse()
        assertThat(state.showSheet).isFalse()
    }

    @Test
    fun `showSheet sets showSheet true`() {
        sut.showSheet()
        assertThat(sut.uiState.value.showSheet).isTrue()
    }

    @Test
    fun `dismissSheet sets showSheet false`() {
        sut.showSheet()
        sut.dismissSheet()
        assertThat(sut.uiState.value.showSheet).isFalse()
    }

    @Test
    fun `refresh with no permissions produces empty granted state`() {
        whenever(pluginPermissions.collectAllPermissions()).thenReturn(emptyList())
        whenever(pluginPermissions.collectMissingPermissions()).thenReturn(emptyList())

        sut.refresh()

        val state = sut.uiState.value
        assertThat(state.items).isEmpty()
        assertThat(state.hasAnyMissing).isFalse()
        assertThat(state.showSheet).isFalse()
    }

    @Test
    fun `refresh with all permissions granted keeps sheet hidden`() {
        whenever(pluginPermissions.collectAllPermissions()).thenReturn(listOf(dexcomGroup, otherGroup))
        whenever(pluginPermissions.collectMissingPermissions()).thenReturn(emptyList())

        sut.refresh()

        val state = sut.uiState.value
        assertThat(state.items).containsExactly(PermissionItem(dexcomGroup, true), PermissionItem(otherGroup, true)).inOrder()
        assertThat(state.hasAnyMissing).isFalse()
        assertThat(state.showSheet).isFalse()
    }

    @Test
    fun `applicable missing Dexcom permission opens sheet and manual controls still work`() {
        whenever(pluginPermissions.collectAllPermissions()).thenReturn(listOf(dexcomGroup))
        whenever(pluginPermissions.collectMissingPermissions()).thenReturn(listOf(dexcomGroup))

        sut.refresh()

        val state = sut.uiState.value
        assertThat(state.items).containsExactly(PermissionItem(dexcomGroup, false))
        assertThat(state.hasAnyMissing).isTrue()
        assertThat(state.showSheet).isTrue()

        sut.dismissSheet()
        assertThat(sut.uiState.value).isEqualTo(state.copy(showSheet = false))
        sut.showSheet()
        assertThat(sut.uiState.value).isEqualTo(state)
        sut.refresh()
        assertThat(sut.uiState.value).isEqualTo(state)
    }

    @Test
    fun `other missing permission opens sheet again after dismissal and refresh`() {
        whenever(pluginPermissions.collectAllPermissions()).thenReturn(listOf(otherGroup))
        whenever(pluginPermissions.collectMissingPermissions()).thenReturn(listOf(otherGroup))

        sut.refresh()

        val state = sut.uiState.value
        assertThat(state.items).containsExactly(PermissionItem(otherGroup, false))
        assertThat(state.hasAnyMissing).isTrue()
        assertThat(state.showSheet).isTrue()

        sut.dismissSheet()
        assertThat(sut.uiState.value.showSheet).isFalse()
        sut.refresh()
        assertThat(sut.uiState.value).isEqualTo(state)
    }

    @Test
    fun `sheet stays open until all applicable missing permissions are granted`() {
        whenever(pluginPermissions.collectAllPermissions()).thenReturn(listOf(dexcomGroup, otherGroup))
        whenever(pluginPermissions.collectMissingPermissions()).thenReturn(listOf(dexcomGroup, otherGroup))

        sut.refresh()

        val state = sut.uiState.value
        assertThat(state.items).containsExactly(PermissionItem(dexcomGroup, false), PermissionItem(otherGroup, false)).inOrder()
        assertThat(state.hasAnyMissing).isTrue()
        assertThat(state.showSheet).isTrue()

        whenever(pluginPermissions.collectMissingPermissions()).thenReturn(listOf(dexcomGroup))
        sut.refresh()
        assertThat(sut.uiState.value.items).containsExactly(PermissionItem(dexcomGroup, false), PermissionItem(otherGroup, true)).inOrder()
        assertThat(sut.uiState.value.hasAnyMissing).isTrue()
        assertThat(sut.uiState.value.showSheet).isTrue()

        whenever(pluginPermissions.collectMissingPermissions()).thenReturn(emptyList())
        sut.refresh()
        assertThat(sut.uiState.value.items).containsExactly(PermissionItem(dexcomGroup, true), PermissionItem(otherGroup, true)).inOrder()
        assertThat(sut.uiState.value.hasAnyMissing).isFalse()
        assertThat(sut.uiState.value.showSheet).isFalse()
    }

    @Test
    fun `applicable missing Dexcom permission with other permission granted opens sheet`() {
        whenever(pluginPermissions.collectAllPermissions()).thenReturn(listOf(dexcomGroup, otherGroup))
        whenever(pluginPermissions.collectMissingPermissions()).thenReturn(listOf(dexcomGroup))

        sut.refresh()

        val state = sut.uiState.value
        assertThat(state.items).containsExactly(PermissionItem(dexcomGroup, false), PermissionItem(otherGroup, true)).inOrder()
        assertThat(state.hasAnyMissing).isTrue()
        assertThat(state.showSheet).isTrue()
    }

    @Test
    fun `other missing permission with Dexcom permission granted opens sheet`() {
        whenever(pluginPermissions.collectAllPermissions()).thenReturn(listOf(dexcomGroup, otherGroup))
        whenever(pluginPermissions.collectMissingPermissions()).thenReturn(listOf(otherGroup))

        sut.refresh()

        val state = sut.uiState.value
        assertThat(state.items).containsExactly(PermissionItem(dexcomGroup, true), PermissionItem(otherGroup, false)).inOrder()
        assertThat(state.hasAnyMissing).isTrue()
        assertThat(state.showSheet).isTrue()
    }

    @Test
    fun `missing group with Dexcom and another permission opens sheet`() {
        val combinedGroup = dexcomGroup.copy(permissions = dexcomGroup.permissions + otherGroup.permissions)
        whenever(pluginPermissions.collectAllPermissions()).thenReturn(listOf(combinedGroup))
        whenever(pluginPermissions.collectMissingPermissions()).thenReturn(listOf(combinedGroup))

        sut.refresh()

        val state = sut.uiState.value
        assertThat(state.items).containsExactly(PermissionItem(combinedGroup, false))
        assertThat(state.hasAnyMissing).isTrue()
        assertThat(state.showSheet).isTrue()
    }

    @Test
    fun `other missing special permission opens sheet alongside missing Dexcom permission`() {
        val batteryGroup = otherGroup.copy(
            permissions = listOf("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"),
            special = true
        )
        whenever(pluginPermissions.collectAllPermissions()).thenReturn(listOf(dexcomGroup, batteryGroup))
        whenever(pluginPermissions.collectMissingPermissions()).thenReturn(listOf(dexcomGroup, batteryGroup))

        sut.refresh()

        val state = sut.uiState.value
        assertThat(state.items).containsExactly(PermissionItem(dexcomGroup, false), PermissionItem(batteryGroup, false)).inOrder()
        assertThat(state.hasAnyMissing).isTrue()
        assertThat(state.showSheet).isTrue()
    }
}
