We need a way to set environment variables for a particular `Component`. A Component is either an add-on, the stack, a simulator or a bot (and each of these can have multiple containers). We will be setting configuration at the Component level, and contained containers might  use those variables.

I have added a new `ConfigItem` object.  Each `Component` can have a list of items (`Component.getConfiguration()`). Each config item of a particular `VariableType`, has a small amount of metadata such as description, and the choices if  `VariableType.CHOICE`.

Add-ons or other types of component have had their required `configuration` element added to their `.json` file (e.g. `manifest.json`or `default-bot-levels.json`). 

This **Component Configuration** can be accessed from 4 places.

* **Add-Ons** page. Each Add-on, if it has `configuration` elements, should have a cog icon that sits to the right of add-on enabling toggle switch.

* The **Simulators** page, each simulator card should have a cog icon added. For now, just add a link with a cog icon  and  the text "Configuration" at the bottom of the card.

* The **Bots** page. Each bot card should have a cog icon added. For now, just add a link with a cog icon and the text "Configuration" at the bottom of the card.

* The **Configuration** page as a new section "Global Container Configuration" that would render the same contents as `variables.html` described below, but inside this configuration page. This implies our configuration rendering support should be reusable.

Each of these will link to the same page, `variables.html` that will render the appropriate configuration.

* The `variables.html` page should be passed a (new) enum parameter `type` for the container type  that identifies if its for and add-on, stack, bot or simulator. 

* It should also take a 2nd parameter `name` to discrimate  the actual add-on, simulator or bot (there is only 1 stack).  This will basically point to an instance of a `ContainerGroupInstanceData`.

* Knowing the add-on, simulator or bot instance (or stack), we should then be able to infer the `Component` type, which will allow us to get the `configuration` , and so the list of `ConfigItem`. 

* For each `ConfigItem` we render the appropriate HTML form input field for that type along with its label (`name`) and description as  de-emphasised text under the component. The default value comes from `requestFields` map in the `ContainerGroupInstanceData` instance we are currently rendering. If there is no such value, any value  in the `constants` map in the `Component` instance should be used as the input fields default value. For text fields, instead   populate the placeholder value and set the field to a blank value. If the value is not  set, we should remove it from `requestFields` so the default will be save.

* Upon saving, we must update the `reqestFields` map, and then redeploy all container that are associated with the component (like we do with updates) so that all environment variables actually passed on to containers (and used in managed files) are  recreated.

* After save is done, the browser should return to add ons, simulator or bots page depending 
