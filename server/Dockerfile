FROM node:22-alpine
WORKDIR /srv
COPY ai-recovery.mjs ./
USER node
ENV PORT=8080
EXPOSE 8080
CMD ["node", "ai-recovery.mjs"]
