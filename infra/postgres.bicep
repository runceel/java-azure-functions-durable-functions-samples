param location string
param token string
param tags object
param initializerObjectId string
param initializerName string
param subnetId string
param dnsZoneId string

resource server 'Microsoft.DBforPostgreSQL/flexibleServers@2024-08-01' = {
  name: 'psql-${token}'
  location: location
  tags: tags
  sku: {
    name: 'Standard_B1ms'
    tier: 'Burstable'
  }
  properties: {
    version: '16'
    createMode: 'Default'
    authConfig: {
      activeDirectoryAuth: 'Enabled'
      passwordAuth: 'Disabled'
      tenantId: tenant().tenantId
    }
    network: { publicNetworkAccess: 'Disabled' }
    storage: {
      storageSizeGB: 32
      autoGrow: 'Enabled'
    }
    backup: {
      backupRetentionDays: 7
      geoRedundantBackup: 'Disabled'
    }
    highAvailability: { mode: 'Disabled' }
  }
}

resource administrator 'Microsoft.DBforPostgreSQL/flexibleServers/administrators@2024-08-01' = {
  parent: server
  name: initializerObjectId
  properties: {
    principalName: initializerName
    principalType: 'ServicePrincipal'
    tenantId: tenant().tenantId
  }
}

resource database 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2024-08-01' = {
  parent: server
  name: 'workshop'
  properties: {
    charset: 'UTF8'
    collation: 'en_US.utf8'
  }
}

module privateEndpoint './private-endpoint.bicep' = {
  params: {
    name: 'pe-postgres-${token}'
    location: location
    tags: tags
    resourceId: server.id
    groupId: 'postgresqlServer'
    subnetId: subnetId
    dnsZoneIds: [ dnsZoneId ]
  }
}

output jdbcUrl string = 'jdbc:postgresql://${server.properties.fullyQualifiedDomainName}:5432/workshop'
output adminJdbcUrl string = 'jdbc:postgresql://${server.properties.fullyQualifiedDomainName}:5432/postgres'
