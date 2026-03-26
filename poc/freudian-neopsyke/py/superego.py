from .config import SuperegoConfig
from .model import ActionProposal, ActionType, OriginSource, SuperegoDecision


class DeterministicSuperego:
    def __init__(self, config: SuperegoConfig) -> None:
        self._allow_id_contact_user = config.allow_id_contact_user
        self._allowed_id_action_types = {ActionType.from_raw(t) for t in config.allowed_id_action_types}

    def review(self, action: ActionProposal) -> SuperegoDecision:
        if action.origin == OriginSource.ID:
            if action.type == ActionType.CONTACT_USER and not self._allow_id_contact_user:
                return SuperegoDecision(
                    allow=False,
                    reason_code="ID_POLICY_CONTACT_USER_DENIED",
                    reason="Id-origin user-facing contact is denied by deterministic superego policy.",
                )
            if action.type not in self._allowed_id_action_types:
                return SuperegoDecision(
                    allow=False,
                    reason_code="ID_POLICY_ACTION_NOT_ALLOWLISTED",
                    reason=f"Id-origin action type {action.type.value} is not allowlisted.",
                )

        return SuperegoDecision(
            allow=True,
            reason_code="ALLOW",
            reason="Action allowed by deterministic superego policy.",
        )
