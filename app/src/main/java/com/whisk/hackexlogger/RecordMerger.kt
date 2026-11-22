package com.whisk.hackexlogger

/**
 * The rules for combining two records that describe the same player, and for finding such pairs.
 * Pure apart from the wallet-conflict log.
 */
object RecordMerger {

    /**
     * Combines [base] with [incoming] (incoming's non-null scalars win).
     * - Canonical IP: unmasked beats masked, a concrete IP beats `unknown:*`.
     * - Anti-pollution: when exactly one side is masked, only the unmasked side's wallets, downloads and thefts survive.
     * - Wallets (developer rule 2026-11-20): a wallet-screen wallet is proof and beats log-inferred ones, whose thefts
     *   are then dropped (logged as a conflict). Log-inferred wallets alone are all kept as candidates.
     */
    fun mergeRecords(base: TargetRecord, incoming: TargetRecord): TargetRecord {
        val baseIsMasked = base.isMasked
        val incomingIsMasked = incoming.isMasked
        val canonicalIp = if ((!incomingIsMasked && baseIsMasked) || (base.isTemporary && !incoming.isTemporary)) incoming.ip else base.ip
        val ignoreMaskedData = (baseIsMasked != incomingIsMasked)
        val unmasked = if (!baseIsMasked) base else incoming

        var droppedWallets: List<String> = emptyList()
        val finalSource: WalletSource?
        val finalWallets: List<String>
        if (ignoreMaskedData) {
            finalSource = unmasked.walletSource
            finalWallets = unmasked.wallets
        } else {
            val distinctWallets = (base.wallets + incoming.wallets).distinct()
            val pageSide = when {
                base.walletFromPage && base.wallets.isNotEmpty() -> base
                incoming.walletFromPage && incoming.wallets.isNotEmpty() -> incoming
                else -> null
            }
            if (distinctWallets.size > 1 && pageSide != null) {
                val keep = pageSide.wallets.first()
                droppedWallets = distinctWallets.filter { it != keep }
                WalletConflictLog.log(canonicalIp, keep, droppedWallets.first(), "MergeRecords")
                finalSource = WalletSource.PAGE
                finalWallets = listOf(keep)
            } else if (distinctWallets.size > 1) {
                finalSource = WalletSource.LOG
                finalWallets = distinctWallets
            } else {
                finalSource = when {
                    distinctWallets.isEmpty() -> null
                    base.walletFromPage || incoming.walletFromPage -> WalletSource.PAGE
                    else -> base.walletSource ?: incoming.walletSource
                }
                finalWallets = distinctWallets
            }
        }

        val mergedDownloads = if (ignoreMaskedData) unmasked.downloads else base.downloads + incoming.downloads

        val mergedThefts = if (ignoreMaskedData) {
            unmasked.walletThefts
        } else {
            (base.walletThefts.keys + incoming.walletThefts.keys).filter { it !in droppedWallets }.associateWith { wallet ->
                val listA = base.walletThefts[wallet] ?: emptyList()
                val listB = incoming.walletThefts[wallet] ?: emptyList()
                (listA + listB).distinctBy { "${it.timestampMs}_${it.amount}" }.sortedBy { it.timestampMs }
            }
        }

        return TargetRecord(
            ip = canonicalIp,
            username = incoming.username ?: base.username,
            clan = incoming.clan ?: base.clan,
            level = incoming.level ?: base.level,
            hardware = incoming.hardware ?: base.hardware,
            firewall = incoming.firewall ?: base.firewall,
            encryptor = incoming.encryptor ?: base.encryptor,
            wallets = finalWallets,
            downloads = mergedDownloads,
            walletThefts = mergedThefts,
            lastValidated = incoming.lastValidated ?: base.lastValidated,
            walletSource = finalSource
        )
    }

    /** True if any two records share a username (ignoring case) or a wallet. O(n). */
    fun hasSharedUsernameOrWallet(records: List<TargetRecord>): Boolean {
        val usernames = HashSet<String>()
        val wallets = HashSet<String>()
        for (record in records) {
            val user = record.username
            if (user != null && !usernames.add(caseInsensitiveKey(user))) return true
            for (wallet in record.wallets.toSet()) {
                if (!wallets.add(wallet)) return true
            }
        }
        return false
    }

    /**
     * Merges every pair of records that share a username (ignoring case) or a wallet.
     * @return the merged list, or null when nothing needed merging (the common case, checked in O(n)).
     */
    fun reconcile(input: List<TargetRecord>): List<TargetRecord>? {
        if (input.size < 2) return null
        // Fast path: the pairwise merge below is O(n²) (~0.25 s on 4,300 records on a desktop, several
        // times that on a phone) and almost always finds nothing.
        if (!hasSharedUsernameOrWallet(input)) return null

        val records = input.toMutableList()
        var changed = false
        var i = 0
        while (i < records.size) {
            var j = i + 1
            while (j < records.size) {
                val recA = records[i]
                val recB = records[j]
                val sharesUser = recA.username != null && recB.username != null &&
                    recA.username.equals(recB.username, ignoreCase = true)
                val sharesWallet = recA.wallets.any { recB.wallets.contains(it) }
                if (sharesUser || sharesWallet) {
                    records[i] = mergeRecords(recA, recB)
                    records.removeAt(j)
                    changed = true
                    j--
                }
                j++
            }
            i++
        }
        return if (changed) records else null
    }

    /** Matches String.equals(other, ignoreCase = true): compare each char's upper-then-lower case form. */
    private fun caseInsensitiveKey(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(Character.toLowerCase(Character.toUpperCase(c)))
        return sb.toString()
    }
}
