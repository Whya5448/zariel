docker run --rm --name nginx -p 80:80 -v %cd%/nginx/nginx.conf:/etc/nginx/nginx.conf:ro -v %cd%/dc-eso-kr:/usr/share/nginx/html:ro -d nginx:mainline