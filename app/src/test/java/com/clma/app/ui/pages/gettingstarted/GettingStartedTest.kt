package me.rerere.rikkahub.ui.pages.gettingstarted

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Locks down the first-run checklist's completion rules — the only bit of the guide with
 * real logic. The Compose screen itself is thin; these are the predicates it renders from.
 */
class GettingStartedTest {

    private fun chatModel() = Model(modelId = "gpt-4o", displayName = "gpt-4o", type = ModelType.CHAT)

    private fun settings(
        providers: List<ProviderSetting> = emptyList(),
        assistants: List<Assistant> = listOf(Assistant()),
    ) = Settings(providers = providers, assistants = assistants)

    @Test
    fun `model step is done only when an enabled provider carries a model`() {
        assertFalse(settings().hasConfiguredModel())

        assertTrue(
            settings(
                providers = listOf(
                    ProviderSetting.OpenAI(name = "OpenAI", models = listOf(chatModel()))
                )
            ).hasConfiguredModel()
        )

        // A disabled provider with a model does not count — the user cannot chat with it yet.
        assertFalse(
            settings(
                providers = listOf(
                    ProviderSetting.OpenAI(name = "Off", enabled = false, models = listOf(chatModel()))
                )
            ).hasConfiguredModel()
        )
    }

    @Test
    fun `tools step ignores the default TimeInfo-only assistant`() {
        val steps = evaluateGettingStartedSteps(settings(), deniedPermissions = 0)
        assertFalse(steps.getValue(GettingStartedStep.Tools))
    }

    @Test
    fun `tools step flips once a real tool is enabled`() {
        val steps = evaluateGettingStartedSteps(
            settings(
                assistants = listOf(
                    Assistant(localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.Clipboard))
                )
            ),
            deniedPermissions = 0,
        )
        assertTrue(steps.getValue(GettingStartedStep.Tools))
    }

    @Test
    fun `permissions step is done only when nothing is denied`() {
        assertTrue(
            evaluateGettingStartedSteps(settings(), deniedPermissions = 0)
                .getValue(GettingStartedStep.Permissions)
        )
        assertFalse(
            evaluateGettingStartedSteps(settings(), deniedPermissions = 2)
                .getValue(GettingStartedStep.Permissions)
        )
    }

    @Test
    fun `workspace step tracks whether any assistant is bound`() {
        assertFalse(
            evaluateGettingStartedSteps(settings(), deniedPermissions = 0)
                .getValue(GettingStartedStep.Workspace)
        )

        assertTrue(
            evaluateGettingStartedSteps(
                settings(assistants = listOf(Assistant(workspaceId = Uuid.random()))),
                deniedPermissions = 0,
            ).getValue(GettingStartedStep.Workspace)
        )
    }
}
