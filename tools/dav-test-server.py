#!/usr/bin/env python3
"""最小 WebDAV 服务器，用来验证闪卡的 WebDAV 同步逻辑。

支持 OPTIONS / PROPFIND / MKCOL / GET / PUT，带 Basic 认证。
刻意复现了坚果云的关键行为：
  - 目录不存在时 PUT 返回 409（不会自动建父目录）
  - MKCOL 建目录返回 201，目录已存在返回 405
  - 认证失败返回 401

用法：
  python3 dav_test_server.py --port 8780 --root /tmp/davroot
  python3 dav_test_server.py --port 8780 --no-mkcol     # 模拟不支持 MKCOL 的服务器
  python3 dav_test_server.py --port 8780 --deny-put     # 模拟只读服务器
"""
import argparse, base64, os, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ap = argparse.ArgumentParser()
ap.add_argument('--port', type=int, default=8780)
ap.add_argument('--root', default='/tmp/davroot')
ap.add_argument('--user', default='demo@example.com')
ap.add_argument('--pw', default='appsecret')
ap.add_argument('--no-mkcol', action='store_true', help='MKCOL 一律返回 405（模拟不支持建目录）')
ap.add_argument('--deny-put', action='store_true', help='PUT 返回 403（模拟只读）')
ap.add_argument('--cors', action='store_true',
                help='允许跨域（浏览器里测网页版时需要；真实坚果云没有这个开关）')
args = ap.parse_args()

ROOT = os.path.abspath(args.root)
os.makedirs(ROOT, exist_ok=True)


def local(path):
    p = os.path.abspath(os.path.join(ROOT, path.lstrip('/')))
    if not p.startswith(ROOT):
        return None
    return p


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    server_version = 'DavTest/1.0'

    def log_message(self, fmt, *a):
        sys.stderr.write('  [dav] %s %s\n' % (self.command, self.path))

    def authorized(self):
        h = self.headers.get('Authorization', '')
        if not h.startswith('Basic '):
            return False
        try:
            raw = base64.b64decode(h[6:]).decode('utf-8')
        except Exception:
            return False
        user, _, pw = raw.partition(':')
        return user == args.user and pw == args.pw

    def deny(self, code=401, msg='Unauthorized'):
        if code == 401:
            self.send_response(401)
            self.send_header('WWW-Authenticate', 'Basic realm="nutstore"')
        else:
            self.send_response(code)
        self.cors()
        self.send_header('Content-Length', '0')
        self.end_headers()

    def reply(self, code, body=b'', ctype='text/plain; charset=utf-8'):
        if isinstance(body, str):
            body = body.encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', ctype)
        self.cors()
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def body(self):
        n = int(self.headers.get('Content-Length') or 0)
        return self.rfile.read(n) if n else b''

    def cors(self):
        if not args.cors:
            return
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Headers', 'authorization, depth, content-type, if-match')
        self.send_header('Access-Control-Allow-Methods', 'OPTIONS, PROPFIND, MKCOL, GET, PUT, DELETE')
        self.send_header('Access-Control-Max-Age', '600')

    def do_OPTIONS(self):
        # CORS 预检：浏览器不会在预检里带 Authorization，所以这一支不能查认证
        if args.cors and self.headers.get('Access-Control-Request-Method'):
            self.send_response(200)
            self.cors()
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if not self.authorized():
            return self.deny()
        self.send_response(200)
        self.send_header('Allow', 'OPTIONS, PROPFIND, MKCOL, GET, PUT, DELETE')
        self.send_header('DAV', '1, 2')
        self.cors()
        self.send_header('Content-Length', '0')
        self.end_headers()

    def do_PROPFIND(self):
        if not self.authorized():
            return self.deny()
        self.body()
        p = local(self.path)
        if p is None:
            return self.reply(403)
        if not os.path.isdir(p):
            return self.reply(404, 'not found')
        xml = ('<?xml version="1.0" encoding="utf-8"?>'
               '<D:multistatus xmlns:D="DAV:"><D:response><D:href>%s</D:href>'
               '<D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>'
               '<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>'
               % self.path)
        self.reply(207, xml, 'application/xml; charset=utf-8')

    def do_MKCOL(self):
        if not self.authorized():
            return self.deny()
        if args.no_mkcol:
            return self.reply(405, 'MKCOL not supported')
        p = local(self.path)
        if p is None:
            return self.reply(403)
        if os.path.isdir(p):
            return self.reply(405, 'already exists')
        parent = os.path.dirname(p)
        if not os.path.isdir(parent):
            return self.reply(409, 'parent collection does not exist')
        os.makedirs(p)
        self.reply(201)

    def do_PUT(self):
        if not self.authorized():
            return self.deny()
        if args.deny_put:
            return self.reply(403, 'read only')
        data = self.body()
        p = local(self.path)
        if p is None:
            return self.reply(403)
        parent = os.path.dirname(p)
        if not os.path.isdir(parent):
            return self.reply(409, 'parent collection does not exist')
        with open(p, 'wb') as f:
            f.write(data)
        self.reply(201 if not os.path.exists(p) else 204)

    def do_GET(self):
        if not self.authorized():
            return self.deny()
        p = local(self.path)
        if p is None or not os.path.isfile(p):
            return self.reply(404, 'not found')
        with open(p, 'rb') as f:
            self.reply(200, f.read(), 'application/json')


if __name__ == '__main__':
    srv = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    print('DAV test server on http://127.0.0.1:%d/  root=%s  user=%s' % (args.port, ROOT, args.user))
    srv.serve_forever()
