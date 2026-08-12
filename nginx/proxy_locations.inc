proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host $host;
proxy_set_header Cookie $http_cookie;
proxy_pass_header Set-Cookie;
proxy_connect_timeout 10s;
proxy_send_timeout 60s;
proxy_read_timeout 60s;

location /api/users {
    set $svc_users users:8080;
    proxy_pass http://$svc_users;
}
location /api/admin {
    set $svc_admin admin:8080;
    proxy_pass http://$svc_admin;
}
location /api/status/ {
    set $svc_statusapp status:8080;
    proxy_pass http://$svc_statusapp;
}
location /api/status {
    set $svc_statusapp status:8080;
    proxy_pass http://$svc_statusapp;
}
location /api/mapview {
    set $svc_mapview mapview:8080;
    proxy_pass http://$svc_mapview;
}
location /api/ {
    set $svc_core core:8080;
    proxy_pass http://$svc_core;
}

location /assets/ {
    try_files $uri =404;
    add_header Cache-Control "public, max-age=31536000, immutable";
    add_header X-Content-Type-Options "nosniff" always;
}

location / {
    try_files $uri $uri/ /index.html;
}
