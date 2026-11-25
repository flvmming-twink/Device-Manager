package ru.misis.devicemanager.ui

import android.content.Intent
import android.os.Looper
import android.widget.EditText
import android.widget.Spinner
import androidx.lifecycle.ViewModelProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.misis.devicemanager.DeviceManagerApplication
import ru.misis.devicemanager.R
import ru.misis.devicemanager.data.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = InventoryTestApplication::class)
class InventoryFlowTest {
    private val app get() = RuntimeEnvironment.getApplication() as DeviceManagerApplication
    private val repository get() = app.repository

    @Before fun prepare() {
        repository.close()
        repository.writableDatabase.delete("devices", null, null)
    }
    @After fun cleanUp() { repository.close() }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("UI operation timed out", condition())
    }

    @Test fun createDeviceThroughEditorThenFindByRoomAndInventory() {
        val controller = Robolectric.buildActivity(DeviceEditorActivity::class.java).setup().visible()
        val activity = controller.get()
        val model = ViewModelProvider(activity)[DeviceEditorViewModel::class.java]
        await { !model.state.value!!.loading }
        activity.findViewById<EditText>(R.id.editName).setText("Проектор Epson")
        activity.findViewById<EditText>(R.id.editInventory).setText("НФ-210")
        activity.findViewById<EditText>(R.id.editRoom).setText("210")
        activity.findViewById<android.view.View>(R.id.editorSave).performClick()
        await { activity.isFinishing }
        assertEquals("Проектор Epson", repository.all().single().name)
        controller.pause().stop().destroy()

        val listController = Robolectric.buildActivity(DeviceListActivity::class.java).setup().visible()
        val list = listController.get()
        val listModel = ViewModelProvider(list)[DeviceListViewModel::class.java]
        await { !listModel.state.value!!.loading }
        list.findViewById<EditText>(R.id.deviceSearch).setText("НФ-210 210")
        val recycler = list.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.deviceList)
        await { recycler.adapter!!.itemCount == 1 }
        list.findViewById<EditText>(R.id.deviceSearch).setText("999")
        await { recycler.adapter!!.itemCount == 0 }
        listController.pause().stop().destroy()
    }

    @Test fun writeOffRequiresReasonAndPersistsAfterEditingLocation() {
        val record = (repository.save(Device(name = "Монитор", inventoryNumber = "22",
            organization = "МИСИС", room = "100")) as SaveResult.Saved).device
        val intent = Intent(app, DeviceEditorActivity::class.java).putExtra(DeviceEditorActivity.EXTRA_ID, record.id)
        val controller = Robolectric.buildActivity(DeviceEditorActivity::class.java, intent).setup().visible()
        val activity = controller.get()
        val model = ViewModelProvider(activity)[DeviceEditorViewModel::class.java]
        await { !model.state.value!!.loading }
        assertEquals(model.state.value.toString(), record, model.state.value!!.device)
        activity.findViewById<Spinner>(R.id.editStatus).setSelection(1)
        shadowOf(Looper.getMainLooper()).idle()
        activity.findViewById<android.view.View>(R.id.editorSave).performClick()
        assertFalse(activity.isFinishing)
        assertTrue(model.state.value!!.invalid.contains(DeviceField.WRITE_OFF_REASON))
        activity.findViewById<EditText>(R.id.editReason).setText("Неисправная матрица")
        activity.findViewById<EditText>(R.id.editRoom).setText("Склад")
        activity.findViewById<android.view.View>(R.id.editorSave).performClick()
        await { !model.state.value!!.saving }
        assertTrue(model.state.value.toString(), activity.isFinishing)
        assertEquals(DeviceStatus.WRITE_OFF, repository.find(record.id)!!.status)
        assertEquals("Склад", repository.find(record.id)!!.room)
        assertEquals("Неисправная матрица", repository.find(record.id)!!.writeOffReason)
        controller.pause().stop().destroy()
    }

    @Test fun unsavedDraftSurvivesActivityRecreation() {
        var controller = Robolectric.buildActivity(DeviceEditorActivity::class.java).setup().visible()
        var activity = controller.get()
        val model = ViewModelProvider(activity)[DeviceEditorViewModel::class.java]
        await { !model.state.value!!.loading }
        activity.findViewById<EditText>(R.id.editName).setText("Лабораторный стенд")
        activity.findViewById<EditText>(R.id.editInventory).setText("СТ-5")
        activity.findViewById<EditText>(R.id.editRoom).setText("Лаборатория 2")
        activity.findViewById<Spinner>(R.id.editStatus).setSelection(1)
        activity.findViewById<EditText>(R.id.editReason).setText("Износ")
        controller = controller.recreate()
        activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Лабораторный стенд", activity.findViewById<EditText>(R.id.editName).text.toString())
        assertEquals("СТ-5", activity.findViewById<EditText>(R.id.editInventory).text.toString())
        assertEquals(1, activity.findViewById<Spinner>(R.id.editStatus).selectedItemPosition)
        assertEquals("Износ", activity.findViewById<EditText>(R.id.editReason).text.toString())
        controller.pause().stop().destroy()
    }
}

/** Legacy Robolectric SQLite connections are thread-bound; UI tests use one dispatcher.
 * The real application keeps Dispatchers.IO and is also exercised on an emulator. */
class InventoryTestApplication : DeviceManagerApplication() {
    override val databaseDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
}
