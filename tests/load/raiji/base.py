"""Base dos usuários virtuais: token, headers e atalhos por serviço.

Convenções de nome de requisição (aparecem agrupadas no relatório do Locust):

    auth:  token (password grant)          -> Keycloak via Kong
    api:   GET /rides                      -> monolito Spring via Kong
    chat:  POST /conversations             -> MESSAGE-SERVICE via Kong
    front: GET /                           -> Next.js (SSR)
    front(bff): GET /api/raiji/rides       -> Next.js repassando ao Kong

Cada grupo tem seu próprio "p99", o que permite ver qual serviço está degradando
quando o teste mistura monolito + chat + front.

Sobre os códigos esperados
--------------------------
Neste domínio, 4xx fazem parte do fluxo: 404 quando uma carona sai do ar entre a
busca e o detalhe, 409 quando não há vaga ou o pedido já foi decidido. O Locust,
por padrão, marca todo 4xx como falha. Por isso cada chamada declara `ok=(...)`
com os códigos aceitos; qualquer outro vira falha explícita com o corpo da
resposta na mensagem.
"""

from __future__ import annotations

import random
import time

from locust import HttpUser, between

from raiji import config
from raiji.auth import POOL, TOKENS, Credentials, bearer

# Códigos aceitos quando a chamada não diz outra coisa.
OK_DEFAULT = (200, 201, 202, 204)


class RaijiBase(HttpUser):
    """Base abstrata: nunca é instanciada (Locust ignora classes abstract)."""

    abstract = True
    wait_time = between(0.5, 2.0)

    # ------------------------------------------------------------------ #
    # Identidade
    # ------------------------------------------------------------------ #
    def on_start(self) -> None:
        self.cred: Credentials = self.pick_credentials()

    def pick_credentials(self) -> Credentials:
        return POOL.any()

    @property
    def token(self) -> str | None:
        # Sempre passa pelo cache: ele devolve o token atual e renova sozinho
        # quando faltam menos de TOKEN_REFRESH_MARGIN segundos. Guardar o token
        # na VU faria ela continuar usando um JWT expirado depois de 5 min.
        return TOKENS.get(self.client, self.cred)

    @property
    def auth(self) -> dict:
        return bearer(self.token)

    def relogin(self) -> None:
        TOKENS.invalidate(self.cred.username)

    # ------------------------------------------------------------------ #
    # Atalhos HTTP por serviço
    # ------------------------------------------------------------------ #
    def api(self, method: str, path: str, name: str | None = None,
            ok=OK_DEFAULT, **kwargs):
        """Chamada ao monolito com o token desta vu. `path` NÃO inclui /api."""
        return self.api_token(self.token, method, path, name=name, ok=ok, **kwargs)

    def api_token(self, token: str | None, method: str, path: str,
                  name: str | None = None, ok=OK_DEFAULT, **kwargs):
        """Idem, mas com um Bearer arbitrário (jornadas com dois atores)."""
        kwargs.setdefault("headers", {}).update(bearer(token))
        return self._request(
            method,
            f"{config.API_PREFIX}{path}",
            name or f"api: {method} {path}",
            ok,
            kwargs,
        )

    def chat(self, method: str, path: str, name: str | None = None,
             ok=OK_DEFAULT, **kwargs):
        """Chamada ao MESSAGE-SERVICE (o Kong remove o prefixo /chat)."""
        return self.chat_token(self.token, method, path, name=name, ok=ok, **kwargs)

    def chat_token(self, token: str | None, method: str, path: str,
                   name: str | None = None, ok=OK_DEFAULT, **kwargs):
        kwargs.setdefault("headers", {}).update(bearer(token))
        return self._request(
            method,
            f"{config.CHAT_PREFIX}{path}",
            name or f"chat: {method} {path}",
            ok,
            kwargs,
        )

    def front(self, method: str, path: str, name: str | None = None,
              ok=OK_DEFAULT, **kwargs):
        """Chamada ao Next (página ou BFF), sem token por padrão."""
        return self._request(
            method, config.FRONT + path, name or f"front: {method} {path}", ok, kwargs
        )

    def bff(self, method: str, path: str, name: str | None = None,
            ok=OK_DEFAULT, **kwargs):
        """Chamada ao BFF do Next (repassa o Bearer para o Kong).

        O Next NÃO valida o token: quem valida é o Kong. Por isso é importante
        mandar um token válido, senão medimos só o custo de bufferizar+401.
        """
        kwargs.setdefault("headers", {}).update(self.auth)
        return self._request(
            method,
            config.FRONT + path,
            name or f"front(bff): {method} {path}",
            ok,
            kwargs,
        )

    # ------------------------------------------------------------------ #
    def _request(self, method: str, url: str, name: str, ok, kwargs):
        with self.client.request(
            method,
            url,
            name=name,
            timeout=config.HTTP_TIMEOUT,
            catch_response=True,
            **kwargs,
        ) as resp:
            if resp.status_code in ok:
                resp.success()
            else:
                resp.failure(f"status {resp.status_code}: {resp.text[:180]}")
            return resp

    # ------------------------------------------------------------------ #
    def check_ok(self, resp, *extra_ok: int) -> bool:
        """Confere o status sem mexer no resultado já registrado.

        Os códigos tolerados devem ser passados como `ok=` na própria chamada
        (`self.api(..., ok=(200, 404))`); este helper serve para decidir o que
        fazer depois (ex.: retornar cedo quando a carona não existe mais).
        """
        return resp.status_code in ({200, 201, 202, 204} | set(extra_ok))

    def think(self, minimum: float = 0.2, maximum: float = 1.0) -> None:
        time.sleep(random.uniform(minimum, maximum))
