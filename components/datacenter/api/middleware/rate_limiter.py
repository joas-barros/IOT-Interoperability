"""
rate_limiter.py — Rate limiting por endpoint via slowapi.

Limites configurados por rota:
  /api/v1/auth/token      → 10/minuto  (proteção contra brute force)
  /api/v1/ingest          → 30/minuto  (gateway publica no máximo ~12/min)
  /api/v1/gateway/metrics → 10/minuto  (gateway publica a cada 60s)
  demais GET              → 60/minuto  (dashboard + clientes externos)

Chave de rate limiting: IP do cliente.
Em produção com proxy reverso, configurar FORWARDED_ALLOW_IPS.
"""

from slowapi import Limiter
from slowapi.util import get_remote_address

# Instância global — importada no main.py e nos routers que precisam de limite específico
limiter = Limiter(key_func=get_remote_address, default_limits=["60/minute"])
