package com.example.storevisitmanagementsystem.ui.task

import android.graphics.Color
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.storevisitmanagementsystem.R
import com.example.storevisitmanagementsystem.data.local.db.entity.PhotoEvidenceEntity
import com.example.storevisitmanagementsystem.databinding.ItemPhotoBinding
import java.io.File

class PhotoAdapter(
    private val onPhotoClick: (PhotoEvidenceEntity) -> Unit
) : ListAdapter<PhotoEvidenceEntity, PhotoAdapter.PhotoViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item)
    }

    inner class PhotoViewHolder(private val binding: ItemPhotoBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(photo: PhotoEvidenceEntity) {
            val file = File(photo.localFilePath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    binding.ivPhotoThumbnail.setImageBitmap(bitmap)
                } else {
                    binding.ivPhotoThumbnail.setImageResource(R.drawable.ic_photo)
                }
            } else {
                binding.ivPhotoThumbnail.setImageResource(R.drawable.ic_photo)
            }

            val statusText = when (photo.uploadStatus.uppercase()) {
                "UPLOADED" -> "SYNCED"
                "UPLOAD_FAILED" -> "FAILED"
                else -> "PENDING"
            }
            binding.tvUploadStatus.text = statusText
            val statusColor = when (photo.uploadStatus.uppercase()) {
                "UPLOADED" -> Color.parseColor("#34D399") // sv_success green
                "UPLOAD_FAILED" -> Color.parseColor("#F87171") // error red
                else -> Color.parseColor("#FBBF24") // pending amber
            }
            binding.tvUploadStatus.setTextColor(statusColor)

            binding.root.setOnClickListener {
                onPhotoClick(photo)
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<PhotoEvidenceEntity>() {
        override fun areItemsTheSame(oldItem: PhotoEvidenceEntity, newItem: PhotoEvidenceEntity): Boolean {
            return oldItem.photoId == newItem.photoId
        }

        override fun areContentsTheSame(oldItem: PhotoEvidenceEntity, newItem: PhotoEvidenceEntity): Boolean {
            return oldItem == newItem
        }
    }
}
