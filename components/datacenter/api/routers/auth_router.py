"""
auth_router.py — Endpoint de autenticação (público, sem JWT).

POST /api/v1/auth/token → recebe usuário/senha → retorna JWT Bearer token
"""

from fastapi import APIRouter, HTTPException, Request, status

from api.auth.jwt_handler import (
    ACCESS_TOKEN_EXPIRE_MINUTES,
    TokenRequest,
    TokenResponse,
    authenticate_user,
    create_access_token,
)
from api.middleware.rate_limiter import limiter

router = APIRouter(prefix="/api/v1/auth", tags=["Autenticação"])


@router.post("/token", response_model=TokenResponse, status_code=200)
@limiter.limit("10/minute")
def login(request: Request, body: TokenRequest):
    """
    Autentica o usuário e retorna um Bearer token JWT.

    Perfis disponíveis:
    - **admin**: acesso total (leitura, escrita, ingestão)
    - **viewer**: somente leitura

    Rate limit: 10 requisições/minuto por IP.
    """
    user = authenticate_user(body.username, body.password)
    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Usuário ou senha incorretos.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    token = create_access_token(user["username"], user["role"])

    return TokenResponse(
        access_token=token,
        expires_in=ACCESS_TOKEN_EXPIRE_MINUTES * 60,
        role=user["role"],
    )