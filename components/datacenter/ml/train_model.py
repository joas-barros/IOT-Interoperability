#!/usr/bin/env python3
# =============================================================================
#  train_model.py — Pipeline de Treinamento do Random Forest para Predição
#                   de Tempo Restante de Voo (Bateria)
#
#  Projeto: Arquitetura IoT Interoperável — Extensão ML (TCC)
#  Componente: Datacenter (PC) — execução offline após coleta de dados
#
#  Fluxo:
#    1. Extrai registros históricos do InfluxDB (bucket: drone_data)
#    2. Segmenta em missões individuais por gap de tempo
#    3. Calcula target retrospectivo: tempo real restante até o pouso
#    4. Treina RandomForestRegressor e avalia (RMSE, MAE, R²)
#    5. Exporta modelo como pickle para uso no FastAPI do RPi
#
#  Dependências:
#    pip install influxdb-client pandas scikit-learn joblib matplotlib
#
#  Uso:
#    python train_model.py
#    python train_model.py --host localhost --port 8086 --dry-run
# =============================================================================

import argparse
import sys
import warnings
from pathlib import Path
from datetime import timedelta

import joblib
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from influxdb_client import InfluxDBClient
from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import LabelEncoder

warnings.filterwarnings("ignore", category=FutureWarning)

# =============================================================================
#  CONFIGURAÇÃO — ajuste conforme seu ambiente
# =============================================================================

INFLUX_URL    = "http://localhost:8086"
INFLUX_TOKEN  = "91cb066c4bafabc63006d6677462f9d64df528042a9f2c97771351c323e5db25"       # mesmo token do datacenter/config
INFLUX_ORG    = "iot-ic"
INFLUX_BUCKET = "telemetria"

# Janela de busca: busca todas as missões das últimas N horas
# Aumente se tiver muitas missões coletadas
QUERY_WINDOW_HOURS = 72

# Gap mínimo entre registros para considerar missões distintas (minutos)
# O firmware pausa ~10s entre missões; 2 min é conservador e seguro
MISSION_GAP_MIN = 2

# Número mínimo de registros para uma missão ser considerada válida
# Missões abortadas (< 10 pontos) são descartadas
MIN_RECORDS_PER_MISSION = 10

# Features usadas pelo modelo
# Ordem importa para a predição — não altere sem retreinar
FEATURE_COLS = [
    "phase_enc",   # fase de voo codificada (0=IDLE … 5=LANDING)
    "alt",         # altitude (m)
    "hdg",         # heading (°)
    "vel",         # velocidade (m/s)
    "tmp",         # temperatura (°C)
    "hum",         # umidade (%)
    "bat",         # bateria atual (%)
    "rssi",        # RSSI Wi-Fi (dBm)
    "dr",          # drain rate efetivo do ciclo (%/s) — principal feature
]

TARGET_COL = "remaining_min"   # minutos reais restantes até o pouso

MODEL_OUTPUT  = Path("ml/models/rf_battery_model.pkl")
ENCODER_OUTPUT = Path("ml/models/phase_encoder.pkl")
PLOT_OUTPUT   = Path("ml/img/validation_plot.png")

# =============================================================================
#  1. EXTRAÇÃO DO INFLUXDB
# =============================================================================

# Os nomes dos fields no InfluxDB espelham o NormalizedData normalizado pelo
# gateway — NÃO os nomes curtos do payload do ESP32 (alt, hdg, vel…).
# O gateway Java grava via influx.py usando os atributos do NormalizedData.
#
# Mapeamento: field InfluxDB → coluna do DataFrame (nome usado nas features)
INFLUX_TO_DF = {
    "_time":              "ts",
    "source_id":          "drone_id",
    "flight_phase":       "phase",      # tag, não field — precisa de join
    "alt_m":              "alt",
    "heading_deg":        "hdg",
    "velocity_ms":        "vel",
    "temp_c":             "tmp",
    "hum_pct":            "hum",
    "battery_pct":        "bat",
    "rssi_dbm":           "rssi",
    "drain_rate_pct_s":   "dr",
    "seq":                "seq",
}

# A query Flux usa pivot para transformar fields em colunas.
# flight_phase é uma TAG (não field), por isso é preservada pelo pivot
# automaticamente quando está na rowKey ou é incluída pelo keep.
FLUX_QUERY = """
from(bucket: "{bucket}")
  |> range(start: -{window}h)
  |> filter(fn: (r) => r["_measurement"] == "telemetria")
  |> filter(fn: (r) => r["source_type"] == "DRONE")
  |> filter(fn: (r) => r["_field"] == "alt_m"
                    or r["_field"] == "heading_deg"
                    or r["_field"] == "velocity_ms"
                    or r["_field"] == "temp_c"
                    or r["_field"] == "hum_pct"
                    or r["_field"] == "battery_pct"
                    or r["_field"] == "rssi_dbm"
                    or r["_field"] == "drain_rate_pct_s"
                    or r["_field"] == "seq")
  |> pivot(rowKey: ["_time", "source_id"], columnKey: ["_field"], valueColumn: "_value")
  |> keep(columns: ["_time", "source_id", "flight_phase",
                    "alt_m", "heading_deg", "velocity_ms",
                    "temp_c", "hum_pct", "battery_pct",
                    "rssi_dbm", "drain_rate_pct_s", "seq"])
  |> sort(columns: ["_time"])
"""


def extract_from_influxdb(url: str, token: str, org: str,
                           bucket: str, window_hours: int) -> pd.DataFrame:
    """Extrai todos os registros de telemetria do InfluxDB."""
    print(f"\n[1/4] Conectando ao InfluxDB em {url}...")

    with InfluxDBClient(url=url, token=token, org=org) as client:
        query_api = client.query_api()

        query = FLUX_QUERY.format(bucket=bucket, window=window_hours)
        print(f"      Buscando últimas {window_hours}h de dados...")

        tables = query_api.query_data_frame(query)

    if tables is None or (isinstance(tables, list) and len(tables) == 0):
        print("[ERRO] Nenhum dado retornado. Verifique bucket, token e janela.")
        sys.exit(1)

    # query_data_frame pode retornar lista de DataFrames (um por tabela)
    if isinstance(tables, list):
        df = pd.concat(tables, ignore_index=True)
    else:
        df = tables

    # Renomeia colunas do InfluxDB para os nomes curtos usados no restante
    # do pipeline e nas FEATURE_COLS
    df = df.rename(columns=INFLUX_TO_DF)
    df["ts"] = pd.to_datetime(df["ts"], utc=True)
    df = df.sort_values("ts").reset_index(drop=True)

    # Garante que campos numéricos estão no tipo certo
    numeric_cols = ["alt", "hdg", "vel", "tmp", "hum", "bat", "rssi", "dr"]
    for col in numeric_cols:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    # flight_phase chega como tag string — garante que está presente
    if "phase" not in df.columns:
        print("[AVISO] Coluna 'phase' (flight_phase) ausente — verifique a query Flux.")
        df["phase"] = "UNKNOWN"

    print(f"      {len(df)} registros extraídos.")
    return df


# =============================================================================
#  2. SEGMENTAÇÃO EM MISSÕES + TARGET RETROSPECTIVO
# =============================================================================

def segment_missions(df: pd.DataFrame,
                     gap_min: int = MISSION_GAP_MIN) -> pd.DataFrame:
    """
    Segmenta os registros em missões individuais com base em gap temporal.
    Atribui mission_id incremental a cada missão detectada.
    """
    df = df.copy()
    df["mission_id"] = 0

    gap = timedelta(minutes=gap_min)
    mission_id = 0
    prev_ts = df["ts"].iloc[0]

    ids = []
    for ts in df["ts"]:
        if ts - prev_ts > gap:
            mission_id += 1
        ids.append(mission_id)
        prev_ts = ts

    df["mission_id"] = ids
    n_missions = mission_id + 1
    print(f"      {n_missions} missões detectadas (gap={gap_min}min).")
    return df


def compute_target(df: pd.DataFrame,
                   min_records: int = MIN_RECORDS_PER_MISSION) -> pd.DataFrame:
    """
    Calcula o target retrospectivo para cada registro:
        remaining_min = (timestamp_último_registro_missão - timestamp_atual) / 60s

    Missões com menos de `min_records` pontos são descartadas (abortadas/incompletas).
    """
    rows = []
    discarded = 0

    for mission_id, group in df.groupby("mission_id"):
        group = group.sort_values("ts").reset_index(drop=True)

        if len(group) < min_records:
            discarded += 1
            continue

        last_ts = group["ts"].iloc[-1]

        # Tempo restante real (em minutos) para cada ponto da missão
        group[TARGET_COL] = (last_ts - group["ts"]).dt.total_seconds() / 60.0

        rows.append(group)

    if discarded > 0:
        print(f"      {discarded} missão(ões) descartada(s) por ter < {min_records} registros.")

    result = pd.concat(rows, ignore_index=True)
    print(f"      {len(result)} registros válidos em {result['mission_id'].nunique()} missões.")
    return result


# =============================================================================
#  3. FEATURE ENGINEERING
# =============================================================================

PHASE_ORDER = ["IDLE", "TAKEOFF", "MISSION", "HOVER", "RETURN", "LANDING"]


def build_features(df: pd.DataFrame) -> tuple[pd.DataFrame, LabelEncoder]:
    """
    Prepara as features para o modelo:
    - Codifica fase de voo como inteiro ordinal (preserva ordem lógica)
    - Remove linhas com NaN em qualquer feature ou no target
    - Retorna DataFrame pronto e o encoder (necessário para inferência)
    """
    df = df.copy()

    # Codificação ordinal da fase — usa ordem lógica de voo
    le = LabelEncoder()
    le.classes_ = np.array(PHASE_ORDER)
    df["phase_enc"] = le.transform(
        df["phase"].str.upper().fillna("IDLE")
    )

    # Remove NaN (pode acontecer em registros incompletos via CoAP com perda)
    before = len(df)
    df = df.dropna(subset=FEATURE_COLS + [TARGET_COL])
    after = len(df)
    if before > after:
        print(f"      {before - after} linhas removidas por NaN em features/target.")

    return df, le


# =============================================================================
#  4. TREINAMENTO E AVALIAÇÃO
# =============================================================================

def train_and_evaluate(df: pd.DataFrame) -> tuple[RandomForestRegressor, dict]:
    """
    Treina RandomForestRegressor e retorna o modelo + métricas.

    Estratégia de split: por missão (não por linha) — evita data leakage.
    Se treinássemos aleatoriamente por linha, o modelo veria pontos da
    mesma missão em treino e teste, o que inflaria artificialmente o R².
    """
    print(f"\n[3/4] Treinando RandomForestRegressor...")

    # Split por mission_id para evitar leakage
    mission_ids = df["mission_id"].unique()
    n_test = max(1, int(len(mission_ids) * 0.2))  # 20% das missões para teste

    rng = np.random.default_rng(seed=42)
    test_missions = rng.choice(mission_ids, size=n_test, replace=False)
    train_missions = np.setdiff1d(mission_ids, test_missions)

    train_df = df[df["mission_id"].isin(train_missions)]
    test_df  = df[df["mission_id"].isin(test_missions)]

    X_train = train_df[FEATURE_COLS].values
    y_train = train_df[TARGET_COL].values
    X_test  = test_df[FEATURE_COLS].values
    y_test  = test_df[TARGET_COL].values

    print(f"      Treino: {len(train_missions)} missões ({len(X_train)} pontos)")
    print(f"      Teste:  {len(test_missions)} missão(ões) ({len(X_test)} pontos)")

    model = RandomForestRegressor(
        n_estimators=100,
        max_depth=None,       # árvores completas — dataset pequeno, sem risco de overfitting alto
        min_samples_leaf=3,   # evita folhas com 1-2 pontos (ruído)
        random_state=42,
        n_jobs=-1,
    )
    model.fit(X_train, y_train)

    y_pred = model.predict(X_test)

    rmse = np.sqrt(mean_squared_error(y_test, y_pred))
    mae  = mean_absolute_error(y_test, y_pred)
    r2   = r2_score(y_test, y_pred)

    metrics = {
        "rmse_min": rmse,
        "mae_min":  mae,
        "r2":       r2,
        "n_train_missions": len(train_missions),
        "n_test_missions":  len(test_missions),
        "n_train_points":   len(X_train),
        "n_test_points":    len(X_test),
        "feature_importances": dict(zip(FEATURE_COLS,
                                        model.feature_importances_)),
    }

    print(f"\n      ── Métricas (conjunto de teste) ──────────────────")
    print(f"      RMSE : {rmse:.4f} min")
    print(f"      MAE  : {mae:.4f} min")
    print(f"      R²   : {r2:.4f}")
    print(f"      ────────────────────────────────────────────────────")

    print(f"\n      ── Importância das features ─────────────────────")
    sorted_imp = sorted(metrics["feature_importances"].items(),
                        key=lambda x: x[1], reverse=True)
    for feat, imp in sorted_imp:
        bar = "█" * int(imp * 40)
        print(f"      {feat:12s} {imp:.4f}  {bar}")
    print(f"      ────────────────────────────────────────────────────")

    # Comparação com método linear (baseline)
    # baseline: bat / taxa_média_global de drain (mesma fórmula do Digital Twin)
    avg_dr = df.loc[df["phase"] == "RETURN", "dr"].mean()
    if np.isnan(avg_dr) or avg_dr == 0:
        avg_dr = df["dr"].mean()
    y_baseline = test_df["bat"].values / (avg_dr * 60)  # bat(%) / (dr(%/s) * 60s/min)

    rmse_base = np.sqrt(mean_squared_error(y_test, y_baseline))
    mae_base  = mean_absolute_error(y_test, y_baseline)
    r2_base   = r2_score(y_test, y_baseline)

    metrics["baseline_rmse_min"] = rmse_base
    metrics["baseline_mae_min"]  = mae_base
    metrics["baseline_r2"]       = r2_base

    print(f"\n      ── Baseline linear (bat / taxa_média) ──────────")
    print(f"      RMSE : {rmse_base:.4f} min")
    print(f"      MAE  : {mae_base:.4f} min")
    print(f"      R²   : {r2_base:.4f}")
    print(f"      ────────────────────────────────────────────────────")

    improvement_rmse = (rmse_base - rmse) / rmse_base * 100
    print(f"\n      Melhoria RF vs linear: {improvement_rmse:+.1f}% no RMSE")

    # Salva gráfico de validação (predito vs real)
    _save_validation_plot(y_test, y_pred, y_baseline, metrics)

    return model, metrics


def _save_validation_plot(y_true: np.ndarray, y_pred_rf: np.ndarray,
                           y_pred_base: np.ndarray, metrics: dict):
    """Gera gráfico predito vs real para RF e baseline linear."""
    fig, axes = plt.subplots(1, 2, figsize=(12, 5))
    fig.suptitle("Validação da Predição de Tempo Restante de Voo",
                 fontsize=14, fontweight="bold")

    max_val = max(y_true.max(), y_pred_rf.max(), y_pred_base.max()) * 1.05

    for ax, y_pred, label, color, rmse, mae in [
        (axes[0], y_pred_rf,   "Random Forest",  "#2196F3",
         metrics["rmse_min"],       metrics["mae_min"]),
        (axes[1], y_pred_base, "Método Linear",  "#FF5722",
         metrics["baseline_rmse_min"], metrics["baseline_mae_min"]),
    ]:
        ax.scatter(y_true, y_pred, alpha=0.5, s=20, color=color, label=label)
        ax.plot([0, max_val], [0, max_val], "k--", linewidth=1, label="Ideal")
        ax.set_xlim(0, max_val)
        ax.set_ylim(0, max_val)
        ax.set_xlabel("Tempo Real Restante (min)")
        ax.set_ylabel("Tempo Predito Restante (min)")
        ax.set_title(f"{label}\nRMSE={rmse:.4f} min | MAE={mae:.4f} min")
        ax.legend(fontsize=8)
        ax.grid(True, alpha=0.3)

    plt.tight_layout()
    plt.savefig(PLOT_OUTPUT, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"\n      Gráfico salvo em: {PLOT_OUTPUT}")


# =============================================================================
#  5. EXPORT
# =============================================================================

def export_model(model: RandomForestRegressor, le: LabelEncoder):
    """Salva modelo e encoder em disco para uso no FastAPI."""
    joblib.dump(model, MODEL_OUTPUT)
    joblib.dump(le,    ENCODER_OUTPUT)
    print(f"\n[4/4] Modelo salvo em:  {MODEL_OUTPUT}")
    print(f"      Encoder salvo em: {ENCODER_OUTPUT}")
    print(f"      Tamanho do modelo: {MODEL_OUTPUT.stat().st_size / 1024:.1f} KB")


# =============================================================================
#  MODO DRY-RUN — gera dataset sintético para validar o pipeline
# =============================================================================

def generate_synthetic_dataset(n_missions: int = 20) -> pd.DataFrame:
    """
    Gera dataset sintético que imita os dados reais do firmware modificado.
    Útil para validar o pipeline antes de ter dados reais coletados.
    """
    print("      [DRY-RUN] Gerando dataset sintético...")
    rng = np.random.default_rng(42)

    phases_seq = (
        ["IDLE"]    * 2  +
        ["TAKEOFF"] * 3  +
        ["MISSION"] * 12 +
        ["HOVER"]   * 4  +
        ["MISSION"] * 8  +
        ["HOVER"]   * 4  +
        ["RETURN"]  * 8  +
        ["LANDING"] * 3
    )
    drain_base = {
        "IDLE": 0.00, "TAKEOFF": 0.08, "MISSION": 0.05,
        "HOVER": 0.07, "RETURN": 0.05, "LANDING": 0.04,
    }

    rows = []
    base_time = pd.Timestamp("2026-01-01 08:00:00", tz="UTC")

    for mission_id in range(n_missions):
        mission_start = base_time + timedelta(hours=mission_id * 2)
        bat = rng.uniform(40, 100)       # bateria inicial variada
        temp_base = rng.uniform(26, 32)  # temperatura da missão

        for i, phase in enumerate(phases_seq):
            ts = mission_start + timedelta(seconds=i * 5)
            temp = temp_base + rng.normal(0, 0.5)
            temp_factor = 1.0 + (temp - 28.0) * 0.01
            temp_factor = np.clip(temp_factor, 0.85, 1.20)

            hdg = rng.uniform(0, 360)
            wind_factor = 1.0
            if phase in ("MISSION", "RETURN"):
                wind_factor = 1.0 + 0.08 * abs(np.cos(np.radians(hdg)))

            noise = 1.0 + rng.normal(0, 0.02)
            dr = drain_base[phase] * temp_factor * wind_factor * noise
            bat = max(0.0, bat - dr * 5)  # dt = 5s

            rows.append({
                "ts":        ts,
                "drone_id":  "drone_01",
                "phase":     phase,
                "mission_id": mission_id,
                "wp":        0,
                "alt":       10.0 if phase not in ("IDLE", "TAKEOFF", "LANDING") else 0.0,
                "hdg":       hdg,
                "vel":       12.0 if phase == "MISSION" else 0.0,
                "tmp":       temp,
                "hum":       rng.uniform(60, 75),
                "bat":       bat,
                "rssi":      rng.integers(-80, -40),
                "dr":        dr,
                "seq":       mission_id * 1000 + i,
            })

    df = pd.DataFrame(rows)
    print(f"      {len(df)} registros sintéticos em {n_missions} missões.")
    return df


# =============================================================================
#  MAIN
# =============================================================================

def parse_args():
    p = argparse.ArgumentParser(
        description="Treina Random Forest para predição de tempo de voo restante."
    )
    p.add_argument("--host",      default="localhost",   help="Host do InfluxDB")
    p.add_argument("--port",      default=8086, type=int, help="Porta do InfluxDB")
    p.add_argument("--token",     default=INFLUX_TOKEN,  help="Token de autenticação")
    p.add_argument("--org",       default=INFLUX_ORG,    help="Organização no InfluxDB")
    p.add_argument("--bucket",    default=INFLUX_BUCKET, help="Bucket de dados")
    p.add_argument("--window",    default=QUERY_WINDOW_HOURS, type=int,
                   help="Janela de busca em horas (padrão: 72)")
    p.add_argument("--dry-run",   action="store_true",
                   help="Usa dataset sintético em vez do InfluxDB (para testes)")
    return p.parse_args()


def main():
    args = parse_args()

    print("=" * 60)
    print("  Pipeline ML — Predição de Tempo de Voo Restante")
    print("  Random Forest Regressor | Target: retrospectivo real")
    print("=" * 60)

    # ── Etapa 1: Extração ──────────────────────────────────────
    if args.dry_run:
        print("\n[1/4] Modo dry-run — usando dataset sintético.")
        df_raw = generate_synthetic_dataset(n_missions=20)
        # Em dry-run, missões já estão segmentadas
        df_segmented = df_raw
    else:
        url = f"http://{args.host}:{args.port}"
        df_raw = extract_from_influxdb(
            url, args.token, args.org, args.bucket, args.window
        )
        # ── Etapa 2a: Segmentação em missões ──────────────────
        print(f"\n[2/4] Segmentando missões e calculando target...")
        df_segmented = segment_missions(df_raw)

    # ── Etapa 2b: Target retrospectivo ────────────────────────
    if not args.dry_run:
        pass  # já foi chamado acima com o label correto
    else:
        print(f"\n[2/4] Calculando target retrospectivo...")

    df_with_target = compute_target(df_segmented)

    # ── Etapa 3: Features ─────────────────────────────────────
    print(f"\n      Construindo features...")
    df_features, le = build_features(df_with_target)

    n_missions_valid = df_features["mission_id"].nunique()
    if n_missions_valid < 3:
        print(f"\n[AVISO] Apenas {n_missions_valid} missão(ões) válida(s).")
        print("        Recomendado: ≥ 10 missões para resultado confiável.")
        print("        Use --dry-run para testar o pipeline com dados sintéticos.")
        if n_missions_valid < 2:
            print("[ERRO] Mínimo de 2 missões necessário para treino/teste. Abortando.")
            sys.exit(1)

    # ── Etapa 4: Treinamento ───────────────────────────────────
    model, metrics = train_and_evaluate(df_features)

    # ── Etapa 5: Export ───────────────────────────────────────
    export_model(model, le)

    print("\n" + "=" * 60)
    print("  Treinamento concluído com sucesso.")
    print(f"  RF   → RMSE={metrics['rmse_min']:.4f} min | MAE={metrics['mae_min']:.4f} min")
    print(f"  Base → RMSE={metrics['baseline_rmse_min']:.4f} min | MAE={metrics['baseline_mae_min']:.4f} min")
    imp_rmse = (metrics['baseline_rmse_min'] - metrics['rmse_min']) / metrics['baseline_rmse_min'] * 100
    print(f"  Melhoria: {imp_rmse:+.1f}% no RMSE")
    print("=" * 60)
    print(f"\n  Arquivos gerados:")
    print(f"    {MODEL_OUTPUT}   — modelo para FastAPI")
    print(f"    {ENCODER_OUTPUT} — encoder de fase")
    print(f"    {PLOT_OUTPUT}    — gráfico predito vs real")
    print()


if __name__ == "__main__":
    main()