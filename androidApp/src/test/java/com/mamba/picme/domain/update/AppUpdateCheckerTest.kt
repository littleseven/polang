package com.mamba.picme.domain.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun `remote versionCode greater than current is available`() {
        assertTrue(
            AppUpdateChecker.isUpdateAvailable(
                remote = RemoteBuild(versionCode = 10042, updatedAt = "2026-09-29 12:00"),
                currentVersionCode = 10041,
                installedRemoteKey = null,
            ),
        )
    }

    @Test
    fun `remote versionCode less than current is not available`() {
        assertFalse(
            AppUpdateChecker.isUpdateAvailable(
                remote = RemoteBuild(versionCode = 10040, updatedAt = "2026-09-29 12:00"),
                currentVersionCode = 10041,
                installedRemoteKey = null,
            ),
        )
    }

    @Test
    fun `equal versionCode without prior OTA install is not available`() {
        assertFalse(
            AppUpdateChecker.isUpdateAvailable(
                remote = RemoteBuild(versionCode = 10041, updatedAt = "2026-09-29 12:00"),
                currentVersionCode = 10041,
                installedRemoteKey = null,
            ),
        )
    }

    @Test
    fun `equal versionCode republished build is available`() {
        assertTrue(
            AppUpdateChecker.isUpdateAvailable(
                remote = RemoteBuild(versionCode = 10041, updatedAt = "2026-09-29 13:00"),
                currentVersionCode = 10041,
                installedRemoteKey = "10041@2026-09-29 12:00",
            ),
        )
    }

    @Test
    fun `equal versionCode same build already installed is not available`() {
        assertFalse(
            AppUpdateChecker.isUpdateAvailable(
                remote = RemoteBuild(versionCode = 10041, updatedAt = "2026-09-29 12:00"),
                currentVersionCode = 10041,
                installedRemoteKey = "10041@2026-09-29 12:00",
            ),
        )
    }

    @Test
    fun `play store installer disables self update`() {
        assertFalse(AppUpdateChecker.isSelfUpdateAllowed("com.android.vending"))
    }

    @Test
    fun `adb install enables self update`() {
        assertTrue(AppUpdateChecker.isSelfUpdateAllowed(null))
    }

    @Test
    fun `package installer enables self update`() {
        assertTrue(AppUpdateChecker.isSelfUpdateAllowed("com.android.packageinstaller"))
    }

    @Test
    fun `other installer enables self update`() {
        assertTrue(AppUpdateChecker.isSelfUpdateAllowed("com.miui.packageinstaller"))
    }
}
