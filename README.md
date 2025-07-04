# SecVers Queue System

A powerful Velocity queue system supporting multiple priority queues with full LuckPerms integration.

---

## FEATURES

- Supports multiple queues: Premium, VIP, Default, and Softban
- Fully compatible with LuckPerms MySQL database
- Modular queue enabling/disabling via config
- Automatic fallback to limbo server
- Queue priority processing: Premium -> VIP -> Default -> Softban (after 5min)
- Individual queue position messages for each player
- Designed for cracked and premium servers, with LibreLogin Support
- Lightweight, optimized for 500+ concurrent queued players

---

## HOW IT WORKS

1. Player joins the limbo/queue server (configure this in Velocity with limboserver setting).
2. The queue system checks the player's group via LuckPerms database (MySQL only).
3. The player is assigned to their respective queue:
   - Premium -> VIP -> Default -> Softban
4. Every tick (5s by default), the system checks if the target server is online and has available slots.
5. Players are sent to the target server based on queue priority.

---

## MAVEN

Add the repository and dependency to your project's pom.xml:

```xml
<repositories>
    <repository>
        <id>secvers-queue-repo</id>
        <url>https://repo.secvers.org/releases</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>org.secverse</groupId>
        <artifactId>secvers-queue</artifactId>
        <version>1.0.0</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

---

## CONFIGURATION (config.yml)

```yaml
# SecVers Queue config.yml

# Enable LuckPerms support.
# Requires a MySQL or MariaDB connection without SSL.
enablelp: false

# The server players join to wait in queue
limboserver: queue

# The target server players will be sent to
targetserver: survival

# Group configuration
# Group names are retrieved from the 'luckperms_user_permissions' table.
# Example: use 'premium' not 'group.premium'.
Groups:
  softban: badgroup
  default: default
  vip: vip
  premium: premium
  displayname_softban: badgroup
  displayname_default: default
  displayname_vip: VIP
  displayname_premium: Premium

# Queue settings
# Enable or disable queues individually
# SoftbanQueueEnabled defaults to allowing join after 5 minutes if other queues are empty
QueueGroupSettings:
  premiumQueueEnabled: true
  vipQueueEnabled: true
  QueueEnabled: true
  SoftbanQueueEnabled: true

# Database connection for LuckPerms integration
database:
  host: 127.0.0.1
  port: 3306
  name: luckperms
  user: root
  pass: ''
```

---

## EXAMPLE FLOW

1. Player joins the limbo server queue.
2. Queue system retrieves their group from LuckPerms MySQL.
3. Adds them to their queue (Premium -> VIP -> Default -> Softban).
4. Every 5 seconds:
   - Checks target server status
   - Moves next player in priority order if slots are available.

---

## NOTES

- LuckPerms support requires MySQL or MariaDB.
- SSL must be disabled in the database connection.
- Softban queue enforces a 5-minute wait if other queues are empty.
- Designed for Velocity + LibreLogin setups.
