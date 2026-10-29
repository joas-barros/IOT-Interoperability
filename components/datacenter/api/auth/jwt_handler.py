"""
jwt_handler.py — Geração e validação de tokens JWT.

Perfis de acesso:
  admin  → leitura + escrita + ingestão
  viewer → somente leitura (GET /api/v1/*)

Fluxo:
  POST /api/v1/auth/token  → recebe usuário/senha → retorna JWT
  Endpoints protegidos     → verificam JWT via Depends(get_current_user)
"""

import os
from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError, jwt
from pydantic import BaseModel

# Configuracao
SECRET_KEY  = os.getenv("JWT_SECRET_KEY", "troque-em-producao-use-openssl-rand-hex-32")
ALGORITHM   = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = int(os.getenv("JWT_EXPIRE_MINUTES", "60"))

USERS_DB: dict[str, dict] = {
    "admin": {
        "password": os.getenv("ADMIN_PASSWORD", "admin123"),
        "role":     "admin",
    },
    "viewer": {
        "password": os.getenv("VIEWER_PASSWORD", "viewer123"),
        "role":     "viewer",
    },
}

security = HTTPBearer()

# ── Modelos ───────────────────────────────────────────────────────────────────

class TokenRequest(BaseModel):
    username: str
    password: str


class TokenResponse(BaseModel):
    access_token: str
    token_type:   str = "bearer"
    expires_in:   int
    role:         str


class UserContext(BaseModel):
    username: str
    role:     str


# ── Funções de token ──────────────────────────────────────────────────────────

def create_access_token(username: str, role: str) -> str:
    """Gera um JWT com claims de usuário e expiração."""
    expire  = datetime.now(timezone.utc) + timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
    payload = {
        "sub":  username,
        "role": role,
        "exp":  expire,
        "iat":  datetime.now(timezone.utc),
    }
    return jwt.encode(payload, SECRET_KEY, algorithm=ALGORITHM)

def authenticate_user(username: str, password: str) -> Optional[dict]:
    """Valida credenciais e retorna o registro do usuário ou None."""
    user = USERS_DB.get(username)
    if not user or user["password"] != password:
        return None
    return {"username": username, **user}


# ── Dependências FastAPI ──────────────────────────────────────────────────────

def get_current_user(
    credentials: HTTPAuthorizationCredentials = Depends(security),
) -> UserContext:
    """
    Dependência injetada nos endpoints protegidos.
    Valida o Bearer token e retorna o contexto do usuário autenticado.
    """
    exc = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Token inválido ou expirado.",
        headers={"WWW-Authenticate": "Bearer"},
    )
    try:
        payload  = jwt.decode(credentials.credentials,
                              SECRET_KEY, algorithms=[ALGORITHM])
        username = payload.get("sub")
        role     = payload.get("role")
        if not username or not role:
            raise exc
        return UserContext(username=username, role=role)
    except JWTError:
        raise exc


def require_admin(user: UserContext = Depends(get_current_user)) -> UserContext:
    """Dependência que exige perfil admin."""
    if user.role != "admin":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Acesso restrito a administradores.",
        )
    return user
