package ru.misis.devicemanager.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import ru.misis.devicemanager.R
import ru.misis.devicemanager.data.Device
import ru.misis.devicemanager.data.DeviceStatus

class DeviceAdapter(
    private val open: (Device) -> Unit,
    private val menu: (Device, View) -> Unit,
) : ListAdapter<Device, DeviceAdapter.Holder>(object : DiffUtil.ItemCallback<Device>() {
    override fun areItemsTheSame(oldItem: Device, newItem: Device) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Device, newItem: Device) = oldItem == newItem
}) {
    class Holder(view: View) : RecyclerView.ViewHolder(view)
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val device = getItem(position)
        val view = holder.itemView
        val context = view.context
        fun text(id: Int, value: String) {
            view.findViewById<TextView>(id).apply { text = value; isVisible = value.isNotBlank() }
        }
        text(R.id.cardName, device.name)
        text(R.id.cardInventory, context.getString(R.string.inventory_number_card, device.inventoryNumber))
        text(R.id.cardCategory, device.category)
        text(R.id.cardLocation, listOf(
            device.organization,
            device.building.takeIf(String::isNotBlank)?.let { context.getString(R.string.building_card, it) },
            context.getString(R.string.room_card, device.room),
        ).filterNotNull().joinToString("\n"))
        text(R.id.cardResponsible, if (device.responsible.isBlank()) "" else
            context.getString(R.string.responsible_card, device.responsible))
        val active = device.status == DeviceStatus.ACTIVE
        view.findViewById<TextView>(R.id.cardStatus).apply {
            setText(if (active) R.string.status_active else R.string.status_write_off)
            setTextColor(ContextCompat.getColor(context, if (active) R.color.device_active else R.color.device_write_off))
            background = GradientDrawable().apply {
                cornerRadius = 8 * resources.displayMetrics.density
                setColor(ContextCompat.getColor(context, if (active) R.color.device_active_bg else R.color.device_write_off_bg))
            }
        }
        view.setOnClickListener { open(device) }
        view.findViewById<ImageButton>(R.id.cardMenu).apply {
            contentDescription = context.getString(R.string.device_actions, device.name)
            setOnClickListener { menu(device, it) }
        }
    }
}
