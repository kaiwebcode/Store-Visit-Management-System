package com.example.storevisitmanagementsystem.ui.task

import android.graphics.Color
import android.graphics.Paint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.storevisitmanagementsystem.R
import com.example.storevisitmanagementsystem.data.local.db.entity.TaskEntity
import com.example.storevisitmanagementsystem.databinding.ItemTaskBinding

class TaskAdapter(
    private val onTaskClick: (TaskEntity) -> Unit,
    private val onCompleteToggle: (TaskEntity) -> Unit,
    private val photoCountProvider: ((taskId: String, callback: (Int) -> Unit) -> Unit)? = null
) : ListAdapter<TaskEntity, TaskAdapter.TaskViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val binding = ItemTaskBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return TaskViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class TaskViewHolder(private val binding: ItemTaskBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(task: TaskEntity) {
            binding.tvTaskTitle.text = task.title
            binding.tvTaskDescription.text = task.description.ifBlank { "No description provided." }
            binding.tvCategoryBadge.text = task.category
            binding.tvPriorityBadge.text = task.priority
            val syncBadgeText = when (task.syncStatus.uppercase()) {
                "SYNCED" -> "SYNCED"
                "SYNCING" -> "SYNCING..."
                "SYNC_FAILED" -> "SYNC FAILED"
                else -> "PENDING"
            }
            binding.tvStatusText.text = "${task.status} | $syncBadgeText"
            val statusColor = when (task.syncStatus.uppercase()) {
                "SYNCED" -> Color.parseColor("#34D399") // sv_success
                "SYNCING" -> Color.parseColor("#1685FF") // sv_accent
                "SYNC_FAILED" -> Color.parseColor("#F87171") // error red
                else -> Color.parseColor("#FBBF24") // pending amber
            }
            binding.tvStatusText.setTextColor(statusColor)

            // Priority Badge Colors
            val priorityColor = when (task.priority.uppercase()) {
                "HIGH" -> Color.parseColor("#F87171")
                "MEDIUM" -> Color.parseColor("#FBBF24")
                "LOW" -> Color.parseColor("#60A5FA")
                else -> Color.parseColor("#A8B5C8")
            }
            binding.tvPriorityBadge.setTextColor(priorityColor)

            // Completion Toggle State & Strike-through Title
            val isCompleted = task.status.uppercase() == "COMPLETED"
            if (isCompleted) {
                binding.btnCompleteToggle.setImageResource(R.drawable.ic_check_circle)
                binding.btnCompleteToggle.setColorFilter(Color.parseColor("#34D399")) // sv_success
                binding.tvTaskTitle.paintFlags = binding.tvTaskTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                binding.btnCompleteToggle.setImageResource(R.drawable.ic_clock)
                binding.btnCompleteToggle.setColorFilter(Color.parseColor("#A8B5C8")) // sv_muted
                binding.tvTaskTitle.paintFlags = binding.tvTaskTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            // Photo Count Callback
            photoCountProvider?.invoke(task.taskId) { count ->
                binding.tvPhotoCount.text = count.toString()
            }

            binding.btnCompleteToggle.setOnClickListener {
                onCompleteToggle(task)
            }

            binding.root.setOnClickListener {
                onTaskClick(task)
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<TaskEntity>() {
        override fun areItemsTheSame(oldItem: TaskEntity, newItem: TaskEntity): Boolean {
            return oldItem.taskId == newItem.taskId
        }

        override fun areContentsTheSame(oldItem: TaskEntity, newItem: TaskEntity): Boolean {
            return oldItem == newItem
        }
    }
}
