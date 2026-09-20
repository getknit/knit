package app.getknit.knit.ui

import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Process
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * [deviceSupervision] folds the platform's policy probes into the one fact the copy needs: who, besides the
 * user, holds this phone's switches. Family Link is pinned by the package the Pixel 8 trial found as profile
 * owner (ADR 2026-09.a8ud), and it wins over the generic "some other owner holds policy" read that any
 * supervised phone also satisfies. The user-restriction bundle is deliberately not read: the system keeps
 * its own keys there.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSupervisionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val devicePolicy get() = shadowOf(app.getSystemService(DevicePolicyManager::class.java))
    private val users get() = shadowOf(app.getSystemService(UserManager::class.java))

    // Robolectric's only public setter is the deprecated per-handle one; the replacement is protected.
    @Suppress("DEPRECATION")
    private fun restrict(key: String) = users.setUserRestriction(Process.myUserHandle(), key, true)

    @Test
    fun `a plain phone is None`() {
        assertEquals(DeviceSupervision.None, deviceSupervision(app))
    }

    @Test
    fun `the Family Link supervision profile owner is FamilyLink`() {
        devicePolicy.setProfileOwner(ComponentName(FAMILY_LINK_SUPERVISION_PACKAGE, "ProfileOwnerReceiver"))
        assertEquals(DeviceSupervision.FamilyLink, deviceSupervision(app))
    }

    @Test
    fun `Family Link wins over the restrictions it also sets`() {
        devicePolicy.setProfileOwner(ComponentName(FAMILY_LINK_SUPERVISION_PACKAGE, "ProfileOwnerReceiver"))
        restrict(UserManager.DISALLOW_CONFIG_LOCATION)
        assertEquals(DeviceSupervision.FamilyLink, deviceSupervision(app))
    }

    @Test
    fun `any other profile owner is Managed`() {
        devicePolicy.setProfileOwner(ComponentName("com.example.emm", "Admin"))
        restrict(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        assertEquals(DeviceSupervision.Managed, deviceSupervision(app))
    }

    @Test
    fun `a device owner is Managed`() {
        devicePolicy.setDeviceOwner(ComponentName("com.example.emm", "Admin"))
        assertEquals(DeviceSupervision.Managed, deviceSupervision(app))
    }

    @Test
    fun `a plain device admin is not Managed`() {
        // Find My Device is an active admin on most consumer phones; only an owner can hold a switch.
        devicePolicy.setActiveAdmin(ComponentName("com.google.android.gms", "FindMyDevice"))
        assertEquals(DeviceSupervision.None, deviceSupervision(app))
    }

    @Test
    fun `a user restriction with no owner behind it is the system's, not Managed`() {
        // An unmanaged Pixel 3 with its bootloader locked carried `no_oem_unlock=true` (2026-09-19): the key is
        // immutable by owners, so a restriction sweep can only misname a consumer phone.
        restrict("no_oem_unlock") // DISALLOW_OEM_UNLOCK left the SDK; the key still lives in the bundle.
        assertEquals(DeviceSupervision.None, deviceSupervision(app))
    }

    @Test
    fun `a restriction key held at false is not a restriction`() {
        // A Pixel 9 with nobody managing it carried `no_record_audio=false` in the bundle (2026-09-17).
        @Suppress("DEPRECATION")
        users.setUserRestriction(Process.myUserHandle(), "no_record_audio", false)
        assertEquals(DeviceSupervision.None, deviceSupervision(app))
    }

    @Test
    fun `a work profile is Managed`() {
        users.setManagedProfile(true)
        assertEquals(DeviceSupervision.Managed, deviceSupervision(app))
    }
}
