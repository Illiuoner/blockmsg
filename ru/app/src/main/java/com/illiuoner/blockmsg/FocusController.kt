package com.illiuoner.blockmsg

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.NotificationManager.Policy
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import java.util.UUID

internal enum class FocusStatus { OFF, READY, ACTIVE, LIMITED, NEEDS_ACCESS, INTERRUPTED, RESTORE }

internal class FocusController(context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val activity = ComponentName(context, MainActivity::class.java)
    private val authority = context.packageName

    fun hasAccess() = manager.isNotificationPolicyAccessGranted

    fun prepare(): FocusChange {
        check(hasAccess())
        return if (Build.VERSION.SDK_INT >= 29) {
            val uri = Uri.Builder().scheme("condition").authority(authority)
                .appendPath("workout").appendPath(UUID.randomUUID().toString()).build()
            FocusChange(ruleUri = uri.toString())
        } else {
            val p = manager.notificationPolicy
            val filter = manager.currentInterruptionFilter
            check(filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
            FocusChange(prior = LegacyPolicy(filter, p.priorityCategories, p.priorityCallSenders,
                p.priorityMessageSenders, p.suppressedVisualEffects))
        }
    }

    fun enable(change: FocusChange) {
        if (Build.VERSION.SDK_INT >= 29 && change.ruleUri != null) {
            val uri = Uri.parse(change.ruleUri)
            val policyBuilder = ZenPolicy.Builder().disallowAllSounds()
                .allowMedia(true)
                .hideAllVisualEffects()
            if (Build.VERSION.SDK_INT >= 35) policyBuilder.allowPriorityChannels(false)
            val policy = policyBuilder.build()
            val rule = if (Build.VERSION.SDK_INT >= 35) {
                AutomaticZenRule.Builder("Тренировка · blockmsg", uri)
                    .setConfigurationActivity(activity)
                    .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    .setZenPolicy(policy)
                    .setType(AutomaticZenRule.TYPE_OTHER)
                    .setTriggerDescription("Активная тренировка в blockmsg")
                    .build()
            } else {
                AutomaticZenRule("Тренировка · blockmsg", null, activity, uri, policy,
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
            }
            val id = findRule(change)?.key ?: checkNotNull(manager.addAutomaticZenRule(rule))
            manager.setAutomaticZenRuleState(id, Condition(uri, "Активная тренировка", Condition.STATE_TRUE))
            return
        }

        val prior = checkNotNull(change.prior)
        check(prior.filter != NotificationManager.INTERRUPTION_FILTER_NONE &&
            prior.filter != NotificationManager.INTERRUPTION_FILTER_ALARMS)
        manager.notificationPolicy = appliedLegacyPolicy(prior)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }

    fun disable(change: FocusChange): Boolean {
        if (!hasAccess()) return false
        return runCatching {
            if (change.ruleUri != null) {
                val entry = findRule(change) ?: return true
                manager.removeAutomaticZenRule(entry.key) || findRule(change) == null
            } else {
                val prior = checkNotNull(change.prior)
                val current = manager.notificationPolicy
                val expected = appliedLegacyPolicy(prior)
                val currentFilter = manager.currentInterruptionFilter
                if (sameLegacyFields(current, expected)) {
                    manager.notificationPolicy = Policy(prior.categories, prior.calls, prior.messages, prior.effects)
                    if (currentFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY &&
                        prior.filter != NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    ) {
                        manager.setInterruptionFilter(prior.filter)
                    }
                }
                true
            }
        }.getOrDefault(false)
    }

    fun status(state: SessionState): FocusStatus {
        if (state.focusEnding) return FocusStatus.RESTORE
        if (!hasAccess()) return FocusStatus.NEEDS_ACCESS
        if (state.workout == null) return FocusStatus.READY
        val change = state.focus ?: return FocusStatus.OFF
        return runCatching {
            val filter = manager.currentInterruptionFilter
            if (filter == NotificationManager.INTERRUPTION_FILTER_ALL ||
                filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            ) return FocusStatus.INTERRUPTED
            if (change.ruleUri != null) {
                val rule = findRule(change) ?: return FocusStatus.INTERRUPTED
                if (!rule.value.isEnabled) return FocusStatus.INTERRUPTED
                if (Build.VERSION.SDK_INT >= 35 &&
                    manager.getAutomaticZenRuleState(rule.key) != Condition.STATE_TRUE
                ) return FocusStatus.INTERRUPTED
            } else if (!sameLegacyFields(manager.notificationPolicy, appliedLegacyPolicy(checkNotNull(change.prior)))) {
                return FocusStatus.INTERRUPTED
            }
            if (filter != NotificationManager.INTERRUPTION_FILTER_PRIORITY) return FocusStatus.LIMITED
            if (Build.VERSION.SDK_INT >= 30) {
                val policy = manager.consolidatedNotificationPolicy
                if (policy.priorityCategories and Policy.PRIORITY_CATEGORY_MEDIA == 0 ||
                    policy.priorityCategories and (Policy.PRIORITY_CATEGORY_CALLS or
                        Policy.PRIORITY_CATEGORY_REPEAT_CALLERS or Policy.PRIORITY_CATEGORY_MESSAGES) != 0 ||
                    policy.suppressedVisualEffects and modernVisualMask != modernVisualMask
                ) return FocusStatus.LIMITED
            }
            FocusStatus.ACTIVE
        }.getOrDefault(FocusStatus.INTERRUPTED)
    }

    fun legacyModeBlocksMedia(): Boolean = Build.VERSION.SDK_INT < 29 &&
        manager.currentInterruptionFilter in listOf(NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS)

    private fun findRule(change: FocusChange) =
        manager.automaticZenRules.entries.firstOrNull { it.value.conditionId.toString() == change.ruleUri }

    private fun appliedLegacyPolicy(prior: LegacyPolicy) =
        Policy(
            if (Build.VERSION.SDK_INT >= 28) Policy.PRIORITY_CATEGORY_MEDIA else 0,
            prior.calls, prior.messages, legacyVisualMask,
        )

    private fun sameLegacyFields(left: Policy, right: Policy) =
        left.priorityCategories == right.priorityCategories &&
            left.priorityCallSenders == right.priorityCallSenders &&
            left.priorityMessageSenders == right.priorityMessageSenders &&
            (left.suppressedVisualEffects and legacyComparisonMask) ==
            (right.suppressedVisualEffects and legacyComparisonMask)

    @Suppress("DEPRECATION")
    private val olderVisualMask = Policy.SUPPRESSED_EFFECT_SCREEN_OFF or Policy.SUPPRESSED_EFFECT_SCREEN_ON

    private val legacyComparisonMask: Int
        get() = if (Build.VERSION.SDK_INT >= 28) modernVisualMask else olderVisualMask

    private val legacyVisualMask: Int
        get() = legacyComparisonMask or olderVisualMask

    private val modernVisualMask =
        Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT or Policy.SUPPRESSED_EFFECT_LIGHTS or
            Policy.SUPPRESSED_EFFECT_PEEK or Policy.SUPPRESSED_EFFECT_STATUS_BAR or
            Policy.SUPPRESSED_EFFECT_BADGE or Policy.SUPPRESSED_EFFECT_AMBIENT or
            Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST
}
