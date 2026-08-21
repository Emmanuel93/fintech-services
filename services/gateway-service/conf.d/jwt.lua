-- jwt.lua — Validación JWT RS256 usando resty.openssl (built-in en OpenResty 1.17+)
-- Sin dependencias externas: usa cjson y resty.openssl.pkey incluidos en la imagen base.

local cjson = require "cjson.safe"
local pkey  = require "resty.openssl.pkey"

local M = {}

local PUBLIC_KEY_PATH = "/etc/nginx/keys/public.pem"
local ISSUER          = "identity-service"

-- Cache del objeto pkey por worker (sobrevive entre requests del mismo worker)
local _cached_pk

local function load_public_key()
    if _cached_pk then return _cached_pk, nil end

    local f, err = io.open(PUBLIC_KEY_PATH, "r")
    if not f then
        return nil, "cannot open public key at " .. PUBLIC_KEY_PATH .. ": " .. (err or "unknown")
    end
    local pem = f:read("*a")
    f:close()

    if not pem or #pem == 0 then
        return nil, "public key file is empty"
    end

    local pk, e = pkey.new(pem, { format = "PEM" })
    if not pk then
        return nil, "failed to parse public key: " .. (e or "unknown")
    end

    _cached_pk = pk
    return pk, nil
end

local function b64url_decode(str)
    str = str:gsub('-', '+'):gsub('_', '/')
    local pad = #str % 4
    if pad == 2 then str = str .. "=="
    elseif pad == 3 then str = str .. "="
    end
    return ngx.decode_base64(str)
end

local function reject(status, reason)
    ngx.status = status
    ngx.header["Content-Type"] = "application/json; charset=utf-8"
    ngx.say('{"error":"unauthorized","reason":"' .. reason .. '"}')
    return ngx.exit(status)
end

local function clear_identity_headers()
    ngx.req.set_header("X-User-Id", nil)
    ngx.req.set_header("X-Roles",   nil)
end

local function clear_channel_header()
    ngx.req.set_header("X-Channel", nil)
end

--- Valida el JWT y, si se pasa `required_channel`, exige que el token venga de ese canal.
--
-- El claim `channel` separa las puertas: un token emitido para la app móvil no debe abrir el
-- backoffice aunque su firma sea válida y sus roles alcancen. Los tokens firmados antes de que
-- existiera el claim se interpretan como MOBILE, que era el único canal posible entonces —
-- el mismo criterio que `Channel.fromClaim()` en identity-service.
--
-- @param required_channel  "MOBILE" | "BACKOFFICE" | "SERVICE" | nil (sin exigencia)
function M.validate(required_channel)
    clear_identity_headers()
    clear_channel_header()

    -- 1. Extraer Bearer token
    local auth = ngx.req.get_headers()["Authorization"]
    if not auth then
        return reject(ngx.HTTP_UNAUTHORIZED, "missing_token")
    end

    local token = auth:match("^[Bb]earer%s+(.+)$")
    if not token then
        return reject(ngx.HTTP_UNAUTHORIZED, "malformed_authorization_header")
    end

    -- 2. Separar en 3 partes
    local parts = {}
    for part in token:gmatch("[^%.]+") do
        table.insert(parts, part)
    end
    if #parts ~= 3 then
        return reject(ngx.HTTP_UNAUTHORIZED, "token_invalid")
    end

    local header_b64, payload_b64, sig_b64 = parts[1], parts[2], parts[3]

    -- 3. Verificar header.alg = RS256
    local header_json = b64url_decode(header_b64)
    local header = header_json and cjson.decode(header_json)
    if not header or header.alg ~= "RS256" then
        return reject(ngx.HTTP_UNAUTHORIZED, "token_invalid")
    end

    -- 4. Decodificar firma
    local sig = b64url_decode(sig_b64)
    if not sig then
        return reject(ngx.HTTP_UNAUTHORIZED, "token_invalid")
    end

    -- 5. Cargar llave pública (cacheada por worker)
    local pk, err = load_public_key()
    if not pk then
        ngx.log(ngx.ERR, "[gateway:jwt] ", err)
        ngx.status = ngx.HTTP_INTERNAL_SERVER_ERROR
        ngx.header["Content-Type"] = "application/json; charset=utf-8"
        ngx.say('{"error":"gateway_error","reason":"key_unavailable"}')
        return ngx.exit(ngx.HTTP_INTERNAL_SERVER_ERROR)
    end

    -- 6. Verificar firma RS256
    local signing_input = header_b64 .. "." .. payload_b64
    local ok, e = pk:verify(sig, signing_input, "sha256")
    if not ok then
        ngx.log(ngx.WARN, "[gateway:jwt] rejected uri=", ngx.var.uri, " reason=", (e or "invalid_signature"))
        return reject(ngx.HTTP_UNAUTHORIZED, "invalid_signature")
    end

    -- 7. Decodificar payload
    local payload_json = b64url_decode(payload_b64)
    local payload = payload_json and cjson.decode(payload_json)
    if not payload then
        return reject(ngx.HTTP_UNAUTHORIZED, "token_invalid")
    end

    -- 8. Verificar expiración
    if payload.exp and payload.exp < ngx.time() then
        return reject(ngx.HTTP_UNAUTHORIZED, "token_expired")
    end

    -- 9. Verificar issuer
    if payload.iss ~= ISSUER then
        ngx.log(ngx.WARN, "[gateway:jwt] invalid issuer: ", tostring(payload.iss))
        return reject(ngx.HTTP_UNAUTHORIZED, "invalid_issuer")
    end

    -- 10. Verificar canal
    local channel = payload.channel or "MOBILE"
    if required_channel and channel ~= required_channel then
        ngx.log(ngx.WARN, "[gateway:jwt] channel mismatch uri=", ngx.var.uri,
                " expected=", required_channel, " actual=", channel)
        return reject(ngx.HTTP_UNAUTHORIZED, "channel_not_allowed")
    end

    -- 11. Propagar identidad al upstream
    ngx.req.set_header("X-User-Id", payload.sub or "")
    ngx.req.set_header("X-Channel", channel)

    local roles = payload.roles
    if type(roles) == "table" then
        ngx.req.set_header("X-Roles", table.concat(roles, ","))
    elseif type(roles) == "string" then
        ngx.req.set_header("X-Roles", roles)
    else
        ngx.req.set_header("X-Roles", "")
    end

    if payload.jti then
        ngx.req.set_header("X-Token-Jti", payload.jti)
    end
end

return M
