# SolaxAutomation

Raspberry Pi automation for a Solax X3-Hybrid-G4 solar inverter — optimizes self-consumption using real-time weather forecasts and spot electricity prices.

![Java](https://img.shields.io/badge/Java-17%2B-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-blue) ![Platform](https://img.shields.io/badge/Platform-Raspberry%20Pi%204B-red)

## About

SolaxAutomation runs on a Raspberry Pi 4B and controls a Solax X3-Hybrid-G4 inverter via Modbus TCP. It fetches hourly weather forecasts from Meteosource and spot electricity prices from spotovaelektrina.cz, then dynamically adjusts inverter behaviour to maximise solar self-consumption and minimise energy costs. Logs rotate daily with automatic compression.

## Features

- Dynamic inverter control via Modbus TCP and GPIO
- Weather-aware operation using Meteosource API forecasts
- Price-driven scheduling via spotovaelektrina.cz spot prices
- Daily log rotation with compression

## Requirements

**Hardware**
- Raspberry Pi 4B running Raspbian
- Solax X3-Hybrid-G4 inverter with Modbus TCP enabled
- *(Optional)* RS485-to-Ethernet converter for systems without direct Modbus TCP

**Software**
- Java 17+
- Maven 3.x

**API keys**
- [Meteosource](https://www.meteosource.com/) account
- Access to [spotovaelektrina.cz](https://www.spotovaelektrina.cz/)

## Setup

```bash
git clone https://github.com/Firestone82/SolaxAutomation.git
cd SolaxAutomation
cp src/main/resources/application.yml.example application.yml
# Edit application.yml with your inverter address, API keys, and coordinates
mvn clean package
java -jar target/solax-automation-*.jar
```

To run as a systemd service, copy the provided unit file and enable it with `systemctl enable solax-automation`.

## License

This project is provided as-is for personal use. No warranty is offered.
