# SolaxAutomation

Raspberry Pi automation for a Solax X3-Hybrid-G4 solar inverter — optimizes self-consumption using real-time weather forecasts and spot electricity prices.

![Java](https://img.shields.io/badge/Java-17%2B-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-blue) ![Platform](https://img.shields.io/badge/Raspberry%20Pi%204B-red)

## About

SolaxAutomation runs on a Raspberry Pi 4B and controls a Solax X3-Hybrid-G4 inverter via Modbus TCP. It fetches hourly weather forecasts from Meteosource and spot electricity prices from spotovaelektrina.cz, then dynamically adjusts inverter behaviour to maximise solar self-consumption and minimise energy costs. Logs rotate daily with automatic compression.

## Features

- Dynamic inverter control via Modbus TCP
- Weather-aware operation using Meteosource API forecasts
- Price-driven scheduling via spotovaelektrina.cz spot prices
- Battery charge/sell automation based on time-of-day and price thresholds
- Configurable power export limits and reduced-output windows
- Daily log rotation with compression

## Requirements

**Hardware**
- Raspberry Pi 4B running Raspbian
- Solax X3-Hybrid-G4 inverter with Modbus TCP enabled
- *(Optional)* RS485-to-Ethernet converter for systems without direct Modbus TCP

**Software**
- Java 17+
- Maven 3.x

**API access**
- [Meteosource](https://www.meteosource.com/) API key (free tier available)
- [spotovaelektrina.cz](https://www.spotovaelektrina.cz/) (no key required)

## Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/Firestone82/SolaxAutomation.git
   cd SolaxAutomation
   ```

2. Copy the example config and fill in your inverter address, Meteosource API key, and location coordinates:
   ```bash
   cp src/main/resources/application.yml.example application.yml
   ```

3. Build the project:
   ```bash
   mvn clean package -DskipTests
   ```

4. Run:
   ```bash
   java -jar target/solax-automation-*.jar
   ```

5. *(Optional)* Install as a systemd service for automatic startup:

   Create `/etc/systemd/system/solax-automation.service`:
   ```ini
   [Unit]
   Description=Solax Automation
   After=network.target

   [Service]
   User=pi
   ExecStart=/usr/bin/java -jar /home/pi/SolaxAutomation/target/solax-automation-*.jar
   WorkingDirectory=/home/pi/SolaxAutomation
   Restart=on-failure

   [Install]
   WantedBy=multi-user.target
   ```

   Then enable and start it:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable --now solax-automation
   ```

## Logs

Logs are written to both console and the `logs/` directory. Log files rotate daily with automatic compression.

## License

This project is provided as-is for personal use. No warranty is offered.
