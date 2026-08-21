-- otel.lua — instrumentación OpenTelemetry del API Gateway (OpenResty), FAIL-OPEN.
--
-- Emite por request 2 spans OTLP/JSON al collector (http://otel-collector:4318/v1/traces):
--   • SERVER  (api-gateway)                → nodo "api-gateway" en el service graph
--   • CLIENT  (→ channel-mobile-service)   → arista api-gateway → BFF
-- y propaga el W3C `traceparent` al upstream, de modo que el BFF y los servicios de dominio
-- continúan la MISMA traza. Resultado en el mapa: user → api-gateway → channel-mobile → services.
--
-- Regla SRE: el tracing NUNCA debe romper la request. Todo va envuelto en pcall y el export es
-- asíncrono (ngx.timer). Si el collector está caído, la request sigue igual.

local _M = {}

local COLLECTOR_HOST = "otel-collector"
local COLLECTOR_PORT = 4318
local SERVICE_NAME   = "api-gateway"
local UPSTREAM_NAME  = "channel-mobile-service"

local random = require "resty.random"
local strlib = require "resty.string"

local function hex(nbytes) return strlib.to_hex(random.bytes(nbytes)) end

-- nanos desde epoch como string EXACTA (evita perder precisión con doubles > 2^53)
local function nanos(t)
  local sec = math.floor(t)
  local ms  = math.floor((t - sec) * 1000 + 0.5)
  return string.format("%d%03d000000", sec, ms)
end

local function esc(v)
  local s = tostring(v)
  s = s:gsub("\\", "\\\\")
  s = s:gsub('"', '\\"')
  return s
end
local function kv(k, v)  return string.format('{"key":"%s","value":{"stringValue":"%s"}}', k, esc(v)) end
local function kvi(k, v) return string.format('{"key":"%s","value":{"intValue":"%d"}}', k, v) end

-- traceparent entrante: 00-<trace(32)>-<span(16)>-<flags(2)>
local function parse_traceparent(tp)
  if not tp then return nil end
  local tr, sp = tp:match("^00%-(%x+)%-(%x+)%-%x%x$")
  if tr and #tr == 32 and #sp == 16 then return tr, sp end
  return nil
end

function _M.start()
  local ok, err = pcall(function()
    local ctx = {}
    local in_tr, in_sp = parse_traceparent(ngx.var.http_traceparent)
    ctx.trace_id  = in_tr or hex(16)     -- reusa la traza si viene una
    ctx.parent_id = in_sp                -- puede ser nil (span raíz)
    ctx.server_id = hex(8)               -- span SERVER del gateway
    ctx.client_id = hex(8)               -- span CLIENT hacia el BFF
    ctx.start     = nanos(ngx.req.start_time())
    ngx.ctx.otel  = ctx
    -- el BFF continúa la traza como hijo del span CLIENT del gateway
    ngx.req.set_header("traceparent", "00-" .. ctx.trace_id .. "-" .. ctx.client_id .. "-01")
  end)
  if not ok then ngx.log(ngx.WARN, "[otel] start: ", err) end
end

local function build_payload(ctx)
  local method   = ngx.var.request_method or "GET"
  local route    = ngx.var.uri or "/"
  local status   = tonumber(ngx.var.status) or 0
  local host     = ngx.var.host or ""
  local finish   = nanos(ngx.now())
  local parent   = ctx.parent_id and (',"parentSpanId":"' .. ctx.parent_id .. '"') or ""

  local server_attrs = table.concat({
    kv("http.request.method", method), kv("url.path", route),
    kv("server.address", host), kvi("http.response.status_code", status),
  }, ",")
  local client_attrs = table.concat({
    kv("server.address", UPSTREAM_NAME), kv("peer.service", UPSTREAM_NAME),
    kv("http.request.method", method), kvi("http.response.status_code", status),
  }, ",")

  local server_span = string.format(
    '{"traceId":"%s","spanId":"%s"%s,"name":"%s %s","kind":2,"startTimeUnixNano":"%s","endTimeUnixNano":"%s","attributes":[%s]}',
    ctx.trace_id, ctx.server_id, parent, method, route, ctx.start, finish, server_attrs)
  local client_span = string.format(
    '{"traceId":"%s","spanId":"%s","parentSpanId":"%s","name":"%s","kind":3,"startTimeUnixNano":"%s","endTimeUnixNano":"%s","attributes":[%s]}',
    ctx.trace_id, ctx.client_id, ctx.server_id, UPSTREAM_NAME, ctx.start, finish, client_attrs)

  return string.format(
    '{"resourceSpans":[{"resource":{"attributes":[%s]},"scopeSpans":[{"scope":{"name":"nginx-otel-lua"},"spans":[%s,%s]}]}]}',
    kv("service.name", SERVICE_NAME), server_span, client_span)
end

-- export asíncrono por cosocket crudo (sin dependencias externas)
local function send(premature, payload)
  if premature then return end
  local sock = ngx.socket.tcp()
  sock:settimeout(2000)
  local ok, err = sock:connect(COLLECTOR_HOST, COLLECTOR_PORT)
  if not ok then ngx.log(ngx.WARN, "[otel] connect: ", err); return end
  local req = table.concat({
    "POST /v1/traces HTTP/1.1",
    "Host: " .. COLLECTOR_HOST .. ":" .. COLLECTOR_PORT,
    "Content-Type: application/json",
    "Content-Length: " .. #payload,
    "Connection: close", "", payload,
  }, "\r\n")
  local _, serr = sock:send(req)
  if serr then ngx.log(ngx.WARN, "[otel] send: ", serr) end
  sock:receive("*l")
  sock:close()
end

function _M.finish()
  local ok, err = pcall(function()
    local ctx = ngx.ctx.otel
    if not ctx then return end
    local tok, terr = ngx.timer.at(0, send, build_payload(ctx))
    if not tok then ngx.log(ngx.WARN, "[otel] timer: ", terr) end
  end)
  if not ok then ngx.log(ngx.WARN, "[otel] finish: ", err) end
end

return _M
