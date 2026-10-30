package com.lumenarcanus.dialife.feature.settings

import com.lumenarcanus.dialife.core.data.DoseSettingsRepository
import com.lumenarcanus.dialife.data.DoseSettings
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.testing.FakeLiveSource
import com.lumenarcanus.dialife.testing.MainDispatcherRule
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DoseSettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private class FakeRepo(var result: Result<DoseSettings>? = null) : DoseSettingsRepository {
        val saved = mutableListOf<DoseSettings>()

        override suspend fun save(d: DoseSettings): Result<DoseSettings> {
            saved += d
            return result ?: Result.success(d)
        }
    }

    private val live = FakeLiveSource(StateResponse(now = 0, dose = DoseSettings(ratio = 12, updatedBy = "Мама")))

    @Test
    fun startsFromTheFamilysSettings() {
        val s = DoseSettingsViewModel(FakeRepo(), live, Locale.US).uiState.value
        assertEquals("12", s.form.ratio)
        assertEquals("Мама", s.updatedBy)
    }

    @Test
    fun aFormWithProblemsIsNotSent() {
        val repo = FakeRepo()
        val vm = DoseSettingsViewModel(repo, live, Locale.US)
        vm.edit(vm.uiState.value.form.copy(ratio = "0"))
        vm.save()
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun savingSaysHowItWent() {
        val repo = FakeRepo()
        val vm = DoseSettingsViewModel(repo, live, Locale.US)
        vm.edit(vm.uiState.value.form.copy(ratio = "15"))
        vm.save()
        assertEquals(15, repo.saved.single().ratio)
        assertEquals(true, vm.uiState.value.saved)
        assertFalse(vm.uiState.value.saving)
        repo.result = Result.failure(RuntimeException())
        vm.save()
        assertEquals(false, vm.uiState.value.saved)
    }

    @Test
    fun whoChangedThemFollowsTheServer() {
        val vm = DoseSettingsViewModel(FakeRepo(), live, Locale.US)
        live.live.value = live.live.value.copy(state = StateResponse(now = 0, dose = DoseSettings(updatedBy = "Тато")))
        assertEquals("Тато", vm.uiState.value.updatedBy)
    }
}
