# Solax Inverter automation
**Author:** Pavel Mikula

A Spring Boot application (Java 17+) running on a Raspberry Pi 4B, designed to dynamically control a Solax X3‑Hybrid‑G4 inverter via Modbus TCP. By leveraging real‑time weather data and energy pricing, this tool maximizes self‑consumption and cost savings for private installations.

> **Note**: This application is intended for private use and may require adjustments to suit different environments. It is provided "as-is" without warranty.

## 🚀 Features
- **Dynamic Inverter Control** through GPIO pins and Modbus TCP
- **Weather‑Aware Operation** using Meteosource API forecasts
- **Price‑Driven Scheduling** via spotovaelektrina.cz (scraped from OTE)
- **Daily Rotating Logs** with automatic compression

## 🧰 Prerequisites

### Hardware
- Raspberry Pi 4B (Raspbian or compatible)
- Solax X3‑Hybrid‑G4 inverter with Modbus TCP enabled
- If no Modbus TCP on inverter: RS485 → Ethernet converter (e.g., Waveshare industrial server)

### APIs
1. **Meteosource**: Request an API key at https://www.meteosource.com/
2. **spotovaelektrina.cz**: Ensure access

### Software
- Java 17 or higher
- Maven 3.x
- Network access between **RPi** and **Solax inverter**

## 🛠 Installation & Setup
1. **Clone the repository**
   ```bash
   git clone https://github.com/Firestone82/SolaxAutomation.git
   cd SolaxAutomation
   ```
2. **Configuration file**
   ```bash
   cp src/main/resources/application.yml.example application.yml
   ```
   Edit `application.yml` (see [Configuration](#configuration) below).
3. **Build**
   ```bash
   mvn clean package
   ```
4. **Run**
   ```bash
   java -jar target/solax-automation-*.jar
   ```
5. *(Optional)* **Install as Service**
   Create a systemd unit `/etc/systemd/system/solax-automation.service`:
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
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable solax-automation
   sudo systemctl start solax-automation
   ```

## ⚙️ Configuration
Edit `application.yml` or set corresponding environment variables.
```yaml
solax:
  modbus:
    # Modbus TCP connection
    host: 192.168.0.31
    port: 502
    # Time management required for modbus
    time:
      # Delay between two requests
      delay: 1000
  # ID of the inverter in modbus, can differ if you have multiple inverters
  unitId: 1
   # Advanced password for the inverter
  password: 2014

# API for electricity prices provided to grid
# - Documentation: https://spotovaelektrina.cz/api
ote:
  api:
    url: "https://spotovaelektrina.cz/api/"

# API for weather data
# - Documentation: https://www.meteosource.com/documentation
meteosource:
  api:
    url: "https://www.meteosource.com/api/"
    key: "YOUR_API_KEY"
  # Location for weather data. How to find is described in the documentation
  placeId: "prague"

# Logging configuration
logging:
  config: classpath:log4j2.xml
```

## 📜 Logs
Logs are provided to console as well as to the files stored in the `logs` directory. The log files are rotated daily and compressed to save space.

## License & Disclaimer
This project is provided "as-is" for personal use. No warranty is offered. Adapt for your needs, but please do not redistribute without permission.