# TODO

## UI

### Setup

 * Focus first field on each setup page.
 * Email field to be optional in user creation (internally default to "<first>.<last>@<cfg.opensimHostname>".
 * Enter key should "submit" (i.e. go to next page) when in text fields (not textarea)
 
### Users

 * Add a checkbox next to "Refresh" button, "Show all agents". By default this is off. When off, only "Root" type agents will be shown.
 * Don's show handler toggle button or select button for non root agents.
  
### Bots

 * Get rid of the console and log action at the very top of the card, the one that opens the console or log for the first container.
 
### Simulators

 * Like Bots, get rid of the console and log action at the very top of the card, the one that opens the console or log for the first container.
 
### Add-Ons

 * Add a warning  above the add-on table that says "Enabling or disabling adds-on may restart simulators, bots or any other stack component it may affect" 
 
### Stack

 * Have 3 columns instead of two, the first column for the icon for when there is an update, heading "Updates" instead of putting it next to the name.
 
### Configuration

 * Docker Hub Username and Docker Hub Token should be on the same row.
 * Add `addOnsRepository` and `addOnsBranch`  to `GridState` and make them both configurable in the updates section too. `AddOnInstanceProvisioningServicee` should  use this if its set, falling back to the value in `SpawnerProperties`. If the path is a git repository, make sure the branch chosen is pull/switched to.
 
## Other
 
 * Redo all bot costumes 
 * Add credits (CC) for default bot costumes
 
 