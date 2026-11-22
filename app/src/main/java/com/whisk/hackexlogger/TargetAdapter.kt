package com.whisk.hackexlogger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Typeface
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * Sealed class representing list items displayed in the targets RecyclerView (Headers vs Target Cards).
 */
sealed class ListItem {
    data class Header(
        val title: String,
        val isPartialHeader: Boolean = false,
        val isExpanded: Boolean = false
    ) : ListItem()

    data class Target(
        val target: TargetRecord
    ) : ListItem()
}

/**
 * DiffUtil callback for efficient list item updates and animations in [TargetAdapter].
 */
class ListItemDiffCallback : DiffUtil.ItemCallback<ListItem>() {
    override fun areItemsTheSame(oldItem: ListItem, newItem: ListItem): Boolean {
        return when {
            oldItem is ListItem.Header && newItem is ListItem.Header -> oldItem.isPartialHeader == newItem.isPartialHeader
            oldItem is ListItem.Target && newItem is ListItem.Target -> oldItem.target.ip == newItem.target.ip
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: ListItem, newItem: ListItem): Boolean {
        return oldItem == newItem
    }
}

/**
 * RecyclerView adapter for displaying headers and HackEx target records.
 */
class TargetAdapter(
    private val onEdit: (TargetRecord) -> Unit,
    private val onDelete: (TargetRecord) -> Unit,
    private val onToggleFull: () -> Unit,
    private val onTogglePartial: () -> Unit
) : ListAdapter<ListItem, RecyclerView.ViewHolder>(ListItemDiffCallback()) {

    var currentValidatingIp: String? = null
    private var activeOverlayPosition: Int = -1

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_TARGET = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ListItem.Header -> TYPE_HEADER
            is ListItem.Target -> TYPE_TARGET
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_header, parent, false)
            HeaderViewHolder(view, onToggleFull, onTogglePartial)
        } else {
            val view = inflater.inflate(R.layout.item_target, parent, false)
            TargetViewHolder(view, onEdit, onDelete)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is ListItem.Target -> {
                val profile = PlayerProfileRepository.getProfile()
                val playerBypasser = PlayerProfileRepository.getEffectiveBypasserLevel()
                val playerCracker = PlayerProfileRepository.getEffectiveCrackerLevel()
                val currentSpamLevel = PlayerProfileRepository.getInstalledSpamLevel()
                (holder as TargetViewHolder).bind(
                    target = item.target,
                    currentValidatingIp = currentValidatingIp,
                    playerBypasser = playerBypasser,
                    playerCracker = playerCracker,
                    playerNetwork = PlayerProfileRepository.getCurrentNetwork(),
                    playerLevel = profile.playerLevel,
                    currentSpamLevel = currentSpamLevel,
                    isOverlayActive = (activeOverlayPosition == position),
                    onToggleOverlay = {
                        val prev = activeOverlayPosition
                        activeOverlayPosition = if (activeOverlayPosition == position) -1 else position
                        if (prev != -1 && prev != position) notifyItemChanged(prev)
                        notifyItemChanged(position)
                    },
                    onDismissOverlay = {
                        if (activeOverlayPosition != -1) {
                            val prev = activeOverlayPosition
                            activeOverlayPosition = -1
                            notifyItemChanged(prev)
                        }
                    }
                )
            }
        }
    }

    class HeaderViewHolder(
        itemView: View,
        private val onToggleFull: () -> Unit,
        private val onTogglePartial: () -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.headerTitle)

        fun bind(header: ListItem.Header) {
            titleText.text = header.title
            if (header.isPartialHeader) {
                titleText.setTextColor(Color.parseColor("#fbbf24"))
                itemView.setOnClickListener { onTogglePartial() }
            } else {
                titleText.setTextColor(Color.parseColor("#60a5fa"))
                itemView.setOnClickListener { onToggleFull() }
            }
        }
    }

    class TargetViewHolder(
        itemView: View,
        private val onEdit: (TargetRecord) -> Unit,
        private val onDelete: (TargetRecord) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        
        private val cardContent: View = itemView.findViewById(R.id.cardContent)
        private val actionOverlay: View = itemView.findViewById(R.id.actionOverlay)
        private val ipText: TextView = itemView.findViewById(R.id.ipText)
        private val identityText: TextView = itemView.findViewById(R.id.identityText)
        private val editBtn: Button = itemView.findViewById(R.id.editBtn)
        private val shareBtn: Button = itemView.findViewById(R.id.shareBtn)
        private val deleteBtn: Button = itemView.findViewById(R.id.deleteBtn)
        
        private val metaContainer: com.google.android.flexbox.FlexboxLayout = itemView.findViewById(R.id.metaContainer)
        private val intelligenceContainer: com.google.android.flexbox.FlexboxLayout = itemView.findViewById(R.id.intelligenceContainer)
        private val walletsContainer: LinearLayout = itemView.findViewById(R.id.walletsContainer)
        
        private val bodySplitLayout: View = itemView.findViewById(R.id.bodySplitLayout)
        private val defenseColumn: View = itemView.findViewById(R.id.defenseColumn)
        private val defenseHeader: TextView = itemView.findViewById(R.id.defenseHeader)
        private val defenseContainer: com.google.android.flexbox.FlexboxLayout = itemView.findViewById(R.id.defenseContainer)
        private val offenseColumn: View = itemView.findViewById(R.id.offenseColumn)
        private val offenseHeader: TextView = itemView.findViewById(R.id.offenseHeader)
        private val offenseContainer: com.google.android.flexbox.FlexboxLayout = itemView.findViewById(R.id.offenseContainer)
        private val virusColumn: View = itemView.findViewById(R.id.virusColumn)
        private val virusHeader: TextView = itemView.findViewById(R.id.virusHeader)
        private val virusContainer: com.google.android.flexbox.FlexboxLayout = itemView.findViewById(R.id.virusContainer)
        private val noSoftwareText: TextView = itemView.findViewById(R.id.noSoftwareText)

        private fun dpToPx(dp: Int, context: android.content.Context): Int {
            return (dp * context.resources.displayMetrics.density).toInt()
        }

        fun bind(
            target: TargetRecord,
            currentValidatingIp: String?,
            playerBypasser: Int,
            playerCracker: Int,
            playerNetwork: String?,
            playerLevel: Int?,
            currentSpamLevel: Int,
            isOverlayActive: Boolean,
            onToggleOverlay: () -> Unit,
            onDismissOverlay: () -> Unit
        ) {
            if (target.isMasked || target.isTemporary) {
                cardContent.setBackgroundResource(R.drawable.bg_node_card_masked)
            } else {
                cardContent.setBackgroundResource(R.drawable.bg_node_card)
            }

            actionOverlay.visibility = if (isOverlayActive) View.VISIBLE else View.GONE

            itemView.setOnLongClickListener {
                onToggleOverlay()
                true
            }

            itemView.setOnClickListener {
                if (isOverlayActive) {
                    onDismissOverlay()
                }
            }
            
            ipText.setOnClickListener {
                if (isOverlayActive) {
                    onDismissOverlay()
                } else {
                    copyToClipboard("IP Address", target.ip)
                }
            }

            actionOverlay.setOnClickListener {
                onDismissOverlay()
            }

            editBtn.setOnClickListener {
                onDismissOverlay()
                onEdit(target)
            }

            shareBtn.setOnClickListener {
                onDismissOverlay()
                shareTarget(target)
            }

            deleteBtn.setOnClickListener {
                onDismissOverlay()
                onDelete(target)
            }

            // --- Row 1: Header (Left: IP, Center: Username, Right: Wallet) ---
            val isCurrentVal = target.ip == currentValidatingIp
            if (isCurrentVal) {
                ipText.text = "👉 ${target.ip}"
                ipText.setTextColor(Color.parseColor("#f59e0b"))
            } else {
                ipText.text = target.ip
                ipText.setTextColor(Color.parseColor("#38bdf8"))
            }

            if (target.username != null) {
                identityText.text = target.username
                identityText.visibility = View.VISIBLE
            } else {
                identityText.visibility = View.INVISIBLE
            }

            // --- Row 2: Rig Chips + Crew Tag ---
            metaContainer.removeAllViews()
            var hasMeta = false
            
            fun addMeta(label: String, value: String) {
                hasMeta = true
                val tv = TextView(itemView.context).apply {
                    layoutParams = com.google.android.flexbox.FlexboxLayout.LayoutParams(
                        com.google.android.flexbox.FlexboxLayout.LayoutParams.WRAP_CONTENT,
                        com.google.android.flexbox.FlexboxLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, dpToPx(3, context), dpToPx(4, context))
                    }
                    typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
                    val fullString = "$label $value"
                    val span = android.text.SpannableString(fullString)
                    span.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#64748b")), 0, label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    span.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#f1f5f9")), label.length + 1, span.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    span.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), label.length + 1, span.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    
                    text = span
                    textSize = 12f
                    setPadding(dpToPx(6, context), dpToPx(3, context), dpToPx(6, context), dpToPx(3, context))
                    setBackgroundResource(R.drawable.bg_chip)
                }
                metaContainer.addView(tv)
            }

            target.level?.let { addMeta("LVL", it.toString()) }
            target.firewall?.let { addMeta("FW", it.toString()) }
            target.encryptor?.let { addMeta("ENC", it.toString()) }
            if (target.hardware != null) {
                val hwStr = target.hardware!!
                // A trailing "?" marks a name missing from HardwareCatalog (maths then uses a fallback)
                if (hwStr.contains(" • ")) {
                    val parts = hwStr.split(" • ")
                    addMeta("DEV", parts[0] + if (HardwareCatalog.device(parts[0]) == null) HardwareCatalog.noteUnknown(parts[0]) else "")
                    if (parts.size > 1) {
                        addMeta("NET", parts[1] + if (HardwareCatalog.network(parts[1]) == null) HardwareCatalog.noteUnknown(parts[1]) else "")
                    }
                } else {
                    addMeta("HW", hwStr + if (HardwareCatalog.parse(hwStr) == null) HardwareCatalog.noteUnknown(hwStr) else "")
                }
            }
            target.clan?.let { addMeta("CREW", it) }

            metaContainer.visibility = if (hasMeta) View.VISIBLE else View.GONE

            // --- Row 3: Intelligence Chips (Bypass Rate, Crack Rate, Upload Duration) ---
            intelligenceContainer.removeAllViews()
            var hasIntelligence = false

            fun addIntelligenceChip(textStr: String, bgTintHex: String, textColorHex: String = "#ffffff"): TextView {
                hasIntelligence = true
                val tv = TextView(itemView.context).apply {
                    layoutParams = com.google.android.flexbox.FlexboxLayout.LayoutParams(
                        com.google.android.flexbox.FlexboxLayout.LayoutParams.WRAP_CONTENT,
                        com.google.android.flexbox.FlexboxLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, dpToPx(3, context), dpToPx(4, context))
                    }
                    typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
                    text = textStr
                    textSize = 11f
                    setTextColor(Color.parseColor(textColorHex))
                    setPadding(dpToPx(6, context), dpToPx(3, context), dpToPx(6, context), dpToPx(3, context))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = dpToPx(4, context).toFloat()
                        setColor(Color.parseColor(bgTintHex))
                    }
                }
                intelligenceContainer.addView(tv)
                return tv
            }

            val bypassRate = target.getBypassSuccessRate(playerBypasser)
            if (bypassRate != null) {
                val fw = target.firewall ?: 0
                val label = if (fw > playerBypasser) "FW +${fw - playerBypasser} ($bypassRate%)" else "Bypass $bypassRate%"
                val colorHex = when {
                    bypassRate >= 58 -> "#065f46"
                    bypassRate == 50 -> "#854d0e"
                    bypassRate >= 34 -> "#9a3412"
                    else -> "#991b1b"
                }
                val chip = addIntelligenceChip(label, colorHex)
                chip.setOnClickListener {
                    val gapStr = if (fw > playerBypasser) "Target Firewall is +${fw - playerBypasser} levels above your Bypasser." else "Your Bypasser matches or exceeds target Firewall."
                    androidx.appcompat.app.AlertDialog.Builder(itemView.context)
                        .setTitle("Bypass Success Rate: $bypassRate%")
                        .setMessage("Your Effective Bypasser: Lv. $playerBypasser\nTarget Firewall: Lv. $fw\n\n$gapStr")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }

            val crackRate = target.getCrackSuccessRate(playerCracker)
            if (crackRate != null) {
                val enc = target.encryptor ?: 0
                val label = if (enc > playerCracker) "ENC +${enc - playerCracker} ($crackRate%)" else "Crack $crackRate%"
                val colorHex = when {
                    crackRate >= 58 -> "#065f46"
                    crackRate == 50 -> "#854d0e"
                    crackRate >= 34 -> "#9a3412"
                    else -> "#991b1b"
                }
                val chip = addIntelligenceChip(label, colorHex)
                chip.setOnClickListener {
                    val gapStr = if (enc > playerCracker) "Target Encryptor is +${enc - playerCracker} levels above your Cracker." else "Your Password Cracker matches or exceeds target Encryptor."
                    androidx.appcompat.app.AlertDialog.Builder(itemView.context)
                        .setTitle("Crack Success Rate: $crackRate%")
                        .setMessage("Your Effective Password Cracker: Lv. $playerCracker\nTarget Encryptor: Lv. $enc\n\n$gapStr")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }

            if (target.hardware != null) {
                // The Spam worth uploading = the cheapest level that hits the 100/hr cap (lower levels upload faster)
                val payoutInfo = GoodSpamUtils.calculateSpamPayoutInfo(target.level, playerLevel, currentSpamLevel)
                val uploadLevel = GoodSpamUtils.spamUploadLevel(payoutInfo, currentSpamLevel)
                val uploadSecs = NetworkSpeedUtils.getUploadSecondsForLevel(target.hardware, playerNetwork, uploadLevel)
                val uploadStr = NetworkSpeedUtils.formatUploadDuration(uploadSecs)
                val colorHex = when {
                    uploadSecs < 15 * 60 -> "#065f46"
                    uploadSecs <= 45 * 60 -> "#854d0e"
                    else -> "#991b1b"
                }
                val levelKnown = playerLevel != null
                val chip = if (levelKnown) addIntelligenceChip("⏱ Spam Lv.$uploadLevel ~$uploadStr", colorHex)
                    else addIntelligenceChip("⏱ Spam Lv.? (your level unknown)", "#334155")
                chip.setOnClickListener {
                    val t1 = NetworkSpeedUtils.formatUploadDuration(NetworkSpeedUtils.getUploadSecondsForLevel(target.hardware, playerNetwork, 1))
                    val t5 = NetworkSpeedUtils.formatUploadDuration(NetworkSpeedUtils.getUploadSecondsForLevel(target.hardware, playerNetwork, 5))
                    val t10 = NetworkSpeedUtils.formatUploadDuration(NetworkSpeedUtils.getUploadSecondsForLevel(target.hardware, playerNetwork, 10))
                    val t20 = NetworkSpeedUtils.formatUploadDuration(NetworkSpeedUtils.getUploadSecondsForLevel(target.hardware, playerNetwork, 20))

                    val netName = playerNetwork ?: "Unknown (scrape your Store)"
                    val devName = target.hardware!!.substringBefore(" • ").trim()

                    val capMessage = if (!levelKnown) {
                        "★ Your own level isn't known yet, so the Spam level that hits the 100/hr cap here can't be worked out.\n" +
                            "   Scrape your own Home screen, then reopen this."
                    } else if (payoutInfo.isCapReachable && uploadLevel >= payoutInfo.recommendedSpamLevel) {
                        "★ Upload Spam Lv.$uploadLevel (~$uploadStr) → hits the 100/hr cap.\n" +
                            "   Anything higher just uploads slower for no extra Crypto."
                    } else if (payoutInfo.isCapReachable) {
                        "★ Spam Lv.${payoutInfo.recommendedSpamLevel} would hit the 100/hr cap here; you have Lv.$currentSpamLevel.\n" +
                            "   Best now: Lv.$uploadLevel (~$uploadStr) → ~${GoodSpamUtils.spamIncome(uploadLevel, payoutInfo.levelMultiplier)}/hr."
                    } else {
                        "★ 100/hr is out of reach on this target (level-gap penalty).\n" +
                            "   Best: Spam Lv.$uploadLevel (~$uploadStr) → ~${GoodSpamUtils.spamIncome(uploadLevel, payoutInfo.levelMultiplier)}/hr."
                    }

                    androidx.appcompat.app.AlertDialog.Builder(itemView.context)
                        .setTitle("Estimated Upload Durations")
                        .setMessage(
                            "Target Device: $devName\n" +
                            "Your Network: $netName\n\n" +
                            "• Virus Lv 1: ~$t1\n" +
                            "• Virus Lv 5: ~$t5\n" +
                            "• Virus Lv 10: ~$t10\n" +
                            "• Virus Lv 20: ~$t20\n\n" +
                            capMessage +
                            "\n\n(Spam payouts: measured in-game for a fresh upload, about 1.7× the guide's table. The level-gap effect is still estimated. Payouts decay after day 21. See Knowledge Base → Spam.)"
                        )
                        .setPositiveButton("OK", null)
                        .show()
                }
            }

            intelligenceContainer.visibility = if (hasIntelligence) View.VISIBLE else View.GONE

            if (target.wallets.isNotEmpty()) {
                walletsContainer.removeAllViews()
                target.wallets.forEach { wallet ->
                    val income = target.getEstimatedIncome(wallet)
                    val tv = TextView(itemView.context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 2, 0, 2)
                        }
                        if (income != null) {
                            val fullString = "$wallet  $income"
                            val span = android.text.SpannableString(fullString)
                            span.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#4ade80")), 0, wallet.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            span.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), wallet.length + 2, fullString.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            span.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), wallet.length + 2, fullString.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            text = span
                        } else {
                            text = wallet
                            setTextColor(Color.parseColor("#4ade80"))
                        }
                        textSize = 12f
                        setTextIsSelectable(true)
                        setBackgroundResource(R.drawable.bg_chip_secondary)
                        setPadding(dpToPx(6, context), dpToPx(3, context), dpToPx(6, context), dpToPx(3, context))
                    }
                    walletsContainer.addView(tv)
                }
                walletsContainer.visibility = View.VISIBLE
            } else {
                walletsContainer.visibility = View.INVISIBLE
            }

            // --- Row 3 Software vs Placeholder ---
            if (target.downloads.isNotEmpty()) {
                bodySplitLayout.visibility = View.VISIBLE
                noSoftwareText.visibility = View.GONE

                defenseContainer.removeAllViews()
                offenseContainer.removeAllViews()
                virusContainer.removeAllViews()

                val defenseOrder = listOf("Firewall", "Password Encryptor", "Antivirus", "Proxy")
                val offenseOrder = listOf("Bypasser", "Password Cracker", "Trace")
                val virusOrder = listOf("Spam", "Siphon", "Rootkit")

                fun addSoftwareChipTo(
                    container: com.google.android.flexbox.FlexboxLayout, 
                    name: String, 
                    data: SoftwareEntry, 
                    valueColor: String,
                    wrapBefore: Boolean = false
                ) {
                    val shortName = when (name) {
                        "Password Encryptor" -> "Encryptor"
                        "Password Cracker" -> "Cracker"
                        else -> name
                    }
                    val tv = TextView(itemView.context).apply {
                        layoutParams = com.google.android.flexbox.FlexboxLayout.LayoutParams(
                            com.google.android.flexbox.FlexboxLayout.LayoutParams.WRAP_CONTENT,
                            com.google.android.flexbox.FlexboxLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, dpToPx(2, context), dpToPx(3, context))
                            isWrapBefore = wrapBefore
                        }
                        typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
                        val fullString = "$shortName ${data.level}"
                        val span = android.text.SpannableString(fullString)
                        span.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor("#94a3b8")), 0, shortName.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        span.setSpan(android.text.style.ForegroundColorSpan(Color.parseColor(valueColor)), shortName.length + 1, span.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        span.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), shortName.length + 1, span.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        
                        text = span
                        textSize = 11.5f
                        setPadding(dpToPx(4, context), dpToPx(2, context), dpToPx(4, context), dpToPx(2, context))
                        setBackgroundResource(R.drawable.bg_chip)
                        
                        if (data.status != "installed") {
                            alpha = 0.5f
                        }
                    }
                    container.addView(tv)
                }

                val downloads = target.downloads

                // Populate Defense (2x2 grid)
                val defenseItems = defenseOrder.mapNotNull { name -> downloads[name]?.let { name to it } }
                if (defenseItems.isNotEmpty()) {
                    defenseColumn.visibility = View.VISIBLE
                    defenseHeader.visibility = View.VISIBLE
                    defenseContainer.visibility = View.VISIBLE
                    defenseItems.forEachIndexed { index, (name, data) ->
                        addSoftwareChipTo(defenseContainer, name, data, "#60a5fa", wrapBefore = (index == 2))
                    }
                } else {
                    defenseColumn.visibility = View.GONE
                    defenseHeader.visibility = View.GONE
                    defenseContainer.visibility = View.GONE
                }

                // Populate Offense (2+1 grid)
                val offenseItems = offenseOrder.mapNotNull { name -> downloads[name]?.let { name to it } }
                if (offenseItems.isNotEmpty()) {
                    offenseColumn.visibility = View.VISIBLE
                    offenseHeader.visibility = View.VISIBLE
                    offenseContainer.visibility = View.VISIBLE
                    offenseItems.forEachIndexed { index, (name, data) ->
                        addSoftwareChipTo(offenseContainer, name, data, "#fb923c", wrapBefore = (index == 2))
                    }
                } else {
                    offenseColumn.visibility = View.GONE
                    offenseHeader.visibility = View.GONE
                    offenseContainer.visibility = View.GONE
                }

                // Populate Viruses (2+1 grid)
                val virusItems = virusOrder.mapNotNull { name -> downloads[name]?.let { name to it } }
                if (virusItems.isNotEmpty()) {
                    virusColumn.visibility = View.VISIBLE
                    virusHeader.visibility = View.VISIBLE
                    virusContainer.visibility = View.VISIBLE
                    virusItems.forEachIndexed { index, (name, data) ->
                        addSoftwareChipTo(virusContainer, name, data, "#34d399", wrapBefore = (index == 2))
                    }
                } else {
                    virusColumn.visibility = View.GONE
                    virusHeader.visibility = View.GONE
                    virusContainer.visibility = View.GONE
                }
            } else {
                bodySplitLayout.visibility = View.GONE
                noSoftwareText.visibility = View.VISIBLE
            }
        }

        private fun copyToClipboard(label: String, text: String) {
            val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(itemView.context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        private fun shareTarget(target: TargetRecord) {
            val sb = java.lang.StringBuilder()
            target.username?.let { sb.append("User: $it\n") }
            sb.append("TARGET: ${target.ip}\n")
            
            if (target.wallets.isNotEmpty()) {
                target.wallets.forEach { wallet ->
                    sb.append("Wallet: $wallet\n")
                }
            }
            
            target.firewall?.let { sb.append("Firewall: Lv.$it\n") }
            target.encryptor?.let { sb.append("Encryptor: Lv.$it\n") }
            
            if (target.downloads.isNotEmpty()) {
                sb.append("\nSoftware:\n")
                target.downloads.forEach { (name, data) ->
                    sb.append("• $name Lv.${data.level}\n")
                }
            }
            
            copyToClipboard("Target Report", sb.toString())
        }
    }
}
