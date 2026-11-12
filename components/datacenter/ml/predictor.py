"""
predictor.py — BatteryPredictor: carrega o modelo Random Forest treinado
               e realiza inferência em tempo real para o Digital Twin.

Integração:
  - Carregado uma única vez no lifespan do FastAPI (main.py)
  - Chamado pelo TwinStore._estimate_return_min() a cada mensagem do drone
  - Fallback automático para o método linear se o modelo não existir

Modo de operação:
  - Se ml/models/rf_battery_model.pkl existir → usa RF
  - Caso contrário → fallback linear (comportamento original do TwinStore)
  - O modo atual fica exposto em predictor.mode ("rf" ou "linear")
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import TYPE_CHECKING, Optional

import numpy as np

if TYPE_CHECKING:
    from api.models import NormalizedData

logger = logging.getLogger(__name__)

# Caminho dos artefatos gerados pelo train_model.py
# (relativo à raiz do componente datacenter)
_BASE_DIR      = Path(__file__).parent
MODELS_DIR     = _BASE_DIR / "models"
MODEL_PATH     = MODELS_DIR / "rf_battery_model.pkl"
ENCODER_PATH   = MODELS_DIR / "phase_encoder.pkl"

# Ordem das features — DEVE ser idêntica à usada no train_model.py
FEATURE_COLS = [
    "phase_enc",
    "alt",
    "hdg",
    "vel",
    "tmp",
    "hum",
    "bat",
    "rssi",
    "dr",
]

# Mapeamento ordinal da fase — fallback quando o encoder não está disponível
PHASE_TO_INT = {
    "IDLE":    0,
    "TAKEOFF": 1,
    "MISSION": 2,
    "HOVER":   3,
    "RETURN":  4,
    "LANDING": 5,
    "UNKNOWN": 0,
}

# Constantes do fallback linear (mesmas do twin_store.py)
BATTERY_DRAIN_PER_S = {
    "IDLE":    0.00,
    "TAKEOFF": 0.08,
    "MISSION": 0.05,
    "HOVER":   0.07,
    "RETURN":  0.05,
    "LANDING": 0.04,
    "UNKNOWN": 0.05,
}
BATTERY_RESERVE_PCT = 25


class BatteryPredictor:
    """
    Preditor de tempo restante de voo baseado em Random Forest.

    Uso:
        predictor = BatteryPredictor()          # carrega no startup
        minutes   = predictor.predict(data)     # chamado a cada mensagem

    Propriedades:
        predictor.mode   → "rf" se modelo carregado, "linear" se fallback
        predictor.ready  → True se modelo RF está disponível
    """

    def __init__(self):
        self._model   = None
        self._encoder = None
        self._load()

    # ── Carregamento ──────────────────────────────────────────────────────────

    def _load(self) -> None:
        """
        Tenta carregar modelo e encoder do disco.
        Falha silenciosa → modo fallback linear ativado automaticamente.
        """
        if not MODEL_PATH.exists():
            logger.warning(
                "[ML] Modelo não encontrado em %s. "
                "Execute ml/train_model.py após coletar dados. "
                "Usando fallback linear.", MODEL_PATH
            )
            return

        try:
            import joblib
            self._model = joblib.load(MODEL_PATH)
            if ENCODER_PATH.exists():
                self._encoder = joblib.load(ENCODER_PATH)
            logger.info(
                "[ML] Modelo RF carregado (%s, %d árvores). Modo: RF.",
                MODEL_PATH.name,
                self._model.n_estimators,
            )
        except Exception as exc:
            logger.error("[ML] Falha ao carregar modelo: %s. Usando fallback.", exc)
            self._model   = None
            self._encoder = None

    def reload(self) -> None:
        """Recarrega o modelo do disco sem reiniciar o servidor."""
        logger.info("[ML] Recarregando modelo...")
        self._model   = None
        self._encoder = None
        self._load()

    # ── Interface pública ─────────────────────────────────────────────────────

    @property
    def mode(self) -> str:
        return "rf" if self._model is not None else "linear"

    @property
    def ready(self) -> bool:
        return self._model is not None

    def predict(self, data: "NormalizedData") -> float:
        """
        Retorna a estimativa de tempo de voo restante em minutos.

        Se o modelo RF estiver carregado, usa a predição do Random Forest.
        Caso contrário, cai no método linear original (bat / drain).

        Args:
            data: NormalizedData recebido do gateway

        Returns:
            float: minutos estimados até precisar pousar
        """
        if self._model is not None:
            return self._predict_rf(data)
        return self._predict_linear(data)

    # ── Implementações ────────────────────────────────────────────────────────

    def _predict_rf(self, data: "NormalizedData") -> float:
        """Predição via Random Forest."""
        try:
            features = self._build_features(data)
            result = self._model.predict(features)[0]
            # Garante valor não negativo
            return round(float(max(0.0, result)), 2)
        except Exception as exc:
            logger.warning("[ML] Falha na predição RF: %s. Usando fallback.", exc)
            return self._predict_linear(data)

    def _predict_linear(self, data: "NormalizedData") -> float:
        """
        Fallback: método linear original do TwinStore.
        bat_utilizável / taxa_de_consumo_da_fase → segundos → minutos
        """
        battery = data.battery_pct or 0.0
        phase   = (data.flight_phase or "UNKNOWN").upper()
        drain   = BATTERY_DRAIN_PER_S.get(phase, 0.05)

        usable = max(0.0, battery - BATTERY_RESERVE_PCT)
        if drain == 0:
            return 999.0

        return round((usable / drain) / 60.0, 1)

    def _build_features(self, data: "NormalizedData") -> np.ndarray:
        """
        Constrói o vetor de features no mesmo formato usado no treinamento.
        Valores ausentes recebem defaults conservadores.
        """
        phase_str = (data.flight_phase or "UNKNOWN").upper()

        # Codificação ordinal da fase
        if self._encoder is not None:
            try:
                phase_enc = int(self._encoder.transform([phase_str])[0])
            except ValueError:
                phase_enc = PHASE_TO_INT.get(phase_str, 0)
        else:
            phase_enc = PHASE_TO_INT.get(phase_str, 0)

        # drain_rate: usa o valor real do payload se disponível;
        # senão usa o drain base da fase como aproximação
        drain_rate = data.drain_rate_pct_s
        if drain_rate is None or drain_rate == 0.0:
            drain_rate = BATTERY_DRAIN_PER_S.get(phase_str, 0.05)

        features = np.array([[
            phase_enc,
            data.alt_m          or 0.0,
            data.heading_deg    or 0.0,
            data.velocity_ms    or 0.0,
            data.temp_c         or 28.0,
            data.hum_pct        or 68.0,
            data.battery_pct    or 0.0,
            data.rssi_dbm       or -70,
            drain_rate,
        ]], dtype=np.float64)

        return features

    # ── Diagnóstico ───────────────────────────────────────────────────────────

    def status(self) -> dict:
        """Retorna informações do predictor para o endpoint /health e /status."""
        info: dict = {"mode": self.mode, "ready": self.ready}
        if self._model is not None:
            info["n_estimators"] = self._model.n_estimators
            info["model_path"]   = str(MODEL_PATH)
            info["features"]     = FEATURE_COLS
        else:
            info["model_path"] = str(MODEL_PATH)
            info["hint"] = "Execute ml/train_model.py para ativar o modo RF."
        return info
