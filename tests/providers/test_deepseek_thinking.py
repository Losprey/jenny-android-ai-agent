"""DeepSeek V4 pensa di default: ``reasoning_effort="none"`` deve spegnerlo sul filo.

Misurato sul telefono il 05/09/2026: il sidecar dell'umore chiedeva una lettera
con ``max_tokens=3`` a ``deepseek-v4-flash`` e riceveva contenuto vuoto con
``finish_reason="length"`` — i tre token finivano nel ragionamento, perché per
quel modello non c'era uno stile di thinking mappato e ``none`` non mandava
niente. La forma del parametro e' quella dei doc DeepSeek (``thinking_mode``):
``{"thinking": {"type": "enabled" | "disabled"}}``.
"""

from __future__ import annotations

from jenny.providers.openai_compat_provider import OpenAICompatProvider

MESSAGES = [{"role": "user", "content": "A or B?"}]


def _kwargs(model: str, effort: str | None) -> dict:
    provider = OpenAICompatProvider(
        api_key="k", api_base="https://api.deepseek.com/v1", default_model=model
    )
    return provider._build_kwargs(MESSAGES, None, model, 3, 0.0, effort, None)


def test_none_disables_thinking_on_deepseek_v4():
    for model in ("deepseek-v4-flash", "deepseek-v4-pro"):
        kw = _kwargs(model, "none")
        assert kw["extra_body"]["thinking"] == {"type": "disabled"}, model
        assert "reasoning_effort" not in kw
        assert kw["max_tokens"] == 3
        assert kw["temperature"] == 0.0


def test_an_explicit_effort_enables_thinking_and_travels_on_the_wire():
    kw = _kwargs("deepseek-v4-flash", "high")
    assert kw["extra_body"]["thinking"] == {"type": "enabled"}
    assert kw["reasoning_effort"] == "high"


def test_no_effort_leaves_the_provider_default_alone():
    kw = _kwargs("deepseek-v4-flash", None)
    assert "thinking" not in kw.get("extra_body", {})
    assert "reasoning_effort" not in kw


def test_unmapped_models_are_untouched():
    kw = _kwargs("some-other-model", "none")
    assert "extra_body" not in kw or "thinking" not in kw["extra_body"]
