FROM nginx:alpine

COPY nginx/nginx.conf /etc/nginx/nginx.conf
COPY index.html form.js styles.css /usr/share/nginx/html/

EXPOSE 80
