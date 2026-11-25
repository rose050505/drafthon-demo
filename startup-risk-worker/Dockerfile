FROM python:3.12-slim-bookworm
WORKDIR /app
COPY app.py compare_market.py risk_assessment.py startup_analysis.py survival_model.py train_survival.py v2v_startup_risk.py ./
COPY dongseongro_agent_model.json dongseongro_startup_public.json ./
ENV STARTUP_RISK_BIND=0.0.0.0
ENV PORT=8080
USER 65534:65534
EXPOSE 8080
ENTRYPOINT ["python", "-B", "app.py"]
