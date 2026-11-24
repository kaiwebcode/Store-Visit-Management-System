package com.example.storevisitmanagementsystem.ui.store

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.storevisitmanagementsystem.data.remote.model.StoreDto
import com.example.storevisitmanagementsystem.databinding.ItemStoreBinding

class StoreAdapter(
    private val onStoreClick: (StoreDto) -> Unit
) : ListAdapter<StoreDto, StoreAdapter.StoreViewHolder>(DiffCallback) {

    private var selectedStoreId: String? = null

    fun setSelectedStoreId(id: String?) {
        val oldId = selectedStoreId
        selectedStoreId = id
        if (oldId != id) {
            notifyDataSetChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoreViewHolder {
        val binding = ItemStoreBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return StoreViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StoreViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, item.id == selectedStoreId)
    }

    inner class StoreViewHolder(private val binding: ItemStoreBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(store: StoreDto, isSelected: Boolean) {
            binding.tvStoreName.text = store.name
            binding.tvStoreCode.text = store.code
            binding.tvStoreAddress.text = store.address
            binding.tvStoreCategory.text = store.category
            binding.tvLastVisited.text = if (store.lastVisited != null) {
                "Last visited: ${store.lastVisited.take(10)}"
            } else {
                "Never visited"
            }

            if (isSelected) {
                binding.cardStore.setStrokeColor(Color.parseColor("#1685FF")) // sv_accent
                binding.cardStore.strokeWidth = 2
                binding.cardStore.setCardBackgroundColor(Color.parseColor("#0D2344")) // sv_selected
                binding.tvSelectedIndicator.visibility = View.VISIBLE
            } else {
                binding.cardStore.setStrokeColor(Color.parseColor("#344252")) // sv_border
                binding.cardStore.strokeWidth = 1
                binding.cardStore.setCardBackgroundColor(Color.parseColor("#19222E")) // sv_surface
                binding.tvSelectedIndicator.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                onStoreClick(store)
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<StoreDto>() {
        override fun areItemsTheSame(oldItem: StoreDto, newItem: StoreDto): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: StoreDto, newItem: StoreDto): Boolean {
            return oldItem == newItem
        }
    }
}
