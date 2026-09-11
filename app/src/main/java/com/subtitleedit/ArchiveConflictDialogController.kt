package com.subtitleedit

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.subtitleedit.databinding.DialogArchiveConflictBinding
import com.subtitleedit.model.ArchiveConflictDialogFormatter
import com.subtitleedit.model.ArchiveConflictDialogModel
import com.subtitleedit.model.ArchiveConflictFileMetadata
import com.subtitleedit.util.ArchiveManager

/** Owns the destination-conflict decision dialog used during extraction. */
internal class ArchiveConflictDialogController(
    private val activity: AppCompatActivity
) {
    fun show(
        conflict: ArchiveManager.DestinationConflict,
        onPolicySelected: (ArchiveManager.ConflictPolicy, Boolean) -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        val model = ArchiveConflictDialogModel(
            entryName = conflict.entryName,
            source = ArchiveConflictFileMetadata(
                sizeBytes = conflict.sourceSize.takeIf { it >= 0L },
                modifiedAtMillis = conflict.sourceModifiedTimeMillis.takeIf { it > 0L }
            ),
            existing = ArchiveConflictFileMetadata(
                sizeBytes = conflict.existingSize.takeIf { it >= 0L },
                modifiedAtMillis = conflict.existingModifiedTimeMillis.takeIf { it > 0L }
            )
        )
        val binding = DialogArchiveConflictBinding.inflate(activity.layoutInflater)
        binding.tvConflictTitle.text = "覆盖文件？"
        binding.tvConflictFileName.text = if (conflict.archiveInternal) {
            "压缩包内重复条目：${model.entryName}"
        } else {
            "（${model.entryName}）已存在"
        }
        binding.tvConflictSourceSize.text =
            "大小：${ArchiveConflictDialogFormatter.size(model.source.sizeBytes)}"
        binding.tvConflictSourceModified.text =
            "最后修改：${ArchiveConflictDialogFormatter.modifiedTime(model.source.modifiedAtMillis)}"
        binding.tvConflictReplacementSize.text =
            "大小：${ArchiveConflictDialogFormatter.size(model.existing.sizeBytes)}"
        binding.tvConflictReplacementModified.text =
            "最后修改：${ArchiveConflictDialogFormatter.modifiedTime(model.existing.modifiedAtMillis)}"

        val dialog = AlertDialog.Builder(activity)
            .setView(binding.root)
            .setCancelable(false)
            .create()
        fun choose(policy: ArchiveManager.ConflictPolicy) {
            val applyToAll = binding.cbApplyToAll.isChecked
            dialog.dismiss()
            onPolicySelected(policy, applyToAll)
        }
        binding.btnConflictCancel.setOnClickListener {
            dialog.dismiss()
            onCancelled()
        }
        binding.btnConflictRename.setOnClickListener { choose(ArchiveManager.ConflictPolicy.RENAME) }
        binding.btnConflictSkip.setOnClickListener { choose(ArchiveManager.ConflictPolicy.SKIP) }
        binding.btnConflictReplace.setOnClickListener { choose(ArchiveManager.ConflictPolicy.OVERWRITE) }
        dialog.show()
    }
}
