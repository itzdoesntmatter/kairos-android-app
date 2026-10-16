package digital.tonima.kairos.core.model

/**
 * Shared constants of the in-app Proton sync bridge (architecture spec §0).
 *
 * Lives in `core:model` (leaf module) so consumers that must not depend on `:proton-sync`
 * — [GetProtonCalendarStatusUseCase], the app-level [CalendarChangeObserver] gate — can
 * reference the account type without pulling in the whole engine.
 *
 * [ACCOUNT_TYPE] is part of the sync contract: it owns every native calendar row written by
 * the bridge, and Android purges those rows when the account is removed (logout cleanup for
 * free, architecture D2). The `:proton-sync` module's [ProtonSyncAccount] delegates to this
 * constant — there is exactly one source of truth. Renaming it orphans every row the old
 * type owns: migrate instead of rename if it ever has to change.
 */
object KairosProtonSync {
    const val ACCOUNT_TYPE = "digital.tonima.kairos.proton"
}
