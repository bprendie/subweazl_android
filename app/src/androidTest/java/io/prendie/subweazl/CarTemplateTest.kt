package io.prendie.subweazl

import android.content.Intent
import androidx.car.app.ScreenManager
import androidx.car.app.model.*
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.testing.SessionController
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarTemplateTest {
    @Test fun templatesAndSessionBridge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var session: SubweazlCarSession
        lateinit var controller: SessionController
        lateinit var context: TestCarContext
        instrumentation.runOnMainSync {
            context = TestCarContext.createCarContext(instrumentation.targetContext)
            session = SubweazlCarSession()
            controller = SessionController(session, context, Intent())
            controller.moveToState(Lifecycle.State.RESUMED)
            ScreenController(context.getCarService(ScreenManager::class.java).top).moveToState(Lifecycle.State.RESUMED)
        }
        val deadline = System.currentTimeMillis() + 15000
        var ready = false
        while (!ready && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                val template = context.getCarService(ScreenManager::class.java).top.onGetTemplate() as TabTemplate
                ready = session.ready && !(template.tabContents.template as SectionedItemTemplate).isLoading
            }
            Thread.sleep(100)
        }
        try {
            instrumentation.runOnMainSync {
                assertTrue(session.connectionError, session.ready)
                val catalogRoot = MediaCatalog(instrumentation.targetContext.repo).item("root")!!
                assertEquals("root", catalogRoot.mediaId)
                assertEquals(true, catalogRoot.mediaMetadata.isBrowsable)
                assertEquals(false, catalogRoot.mediaMetadata.isPlayable)
                val root = context.getCarService(ScreenManager::class.java).top.onGetTemplate()
                assertTrue(root is TabTemplate)
                assertEquals(4, (root as TabTemplate).tabs.size)
                if (instrumentation.targetContext.repo.active.value != null) {
                    val home = root.tabContents.template as SectionedItemTemplate
                    assertEquals(listOf("Recently played", "Newest albums", "Random albums", "Discover"),
                        home.sections.map { it.title.toString() })
                }
                assertTrue(CarPlayingScreen(context, session).onGetTemplate() is MediaPlaybackTemplate)
                assertTrue(CarQueueScreen(context, session).onGetTemplate() is SectionedItemTemplate)
                assertTrue(CarSearchScreen(context, session).onGetTemplate() is SearchTemplate)
                listOf("library", "albums", "radio", "saved", "playlists").forEach {
                    assertTrue(CarBrowseScreen(context, session, it, it).onGetTemplate() is SectionedItemTemplate)
                }
            }
        } finally { instrumentation.runOnMainSync { controller.moveToState(Lifecycle.State.DESTROYED) } }
    }
}
