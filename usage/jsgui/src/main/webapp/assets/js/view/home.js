/**
 * Renders the Applications page. From it we create all other application related views.
 */

define([
    "view/viewutils", 
    "./application-add-wizard", "model/location",
    "text!tpl/home/applications.html",
    "text!tpl/home/summaries.html",
    "text!tpl/home/app-entry.html",
    "bootstrap", "underscore", "jquery", "backbone", "brooklyn-utils"
], function (ViewUtils,
        AppAddWizard, Location, 
        ApplicationsHtml, HomeSummariesHtml, AppEntryHtml) {

    var HomeView = Backbone.View.extend({
        tagName:"div",
        events:{
            'click #add-new-application':'createApplication',
            'click #reload-brooklyn-properties': 'reloadBrooklynProperties',
            'click .addApplication':'createApplication'
        },
        
        summariesView:{},
        
        initialize:function () {
            var that = this
            
            this.$el.html(_.template(ApplicationsHtml, {} ))
            $(".nav1").removeClass("active");
            $(".nav1_home").addClass("active");
            
            this._appViews = {}
            this.summariesView = new HomeView.HomeSummariesView({
                applications:this.collection,
                locations:this.options.locations
            })
            this.renderSummaries()
            
            this.collection.on('reset', this.render, this)
            this.options.locations.on('reset', this.renderSummaries, this)

            ViewUtils.fetchRepeatedlyWithDelay(this, this.collection, 
                    { fetchOptions: { reset: true }, doitnow: true, 
                    /* max is short here so the console becomes usable quickly */
                    backoffMaxPeriod: 10*1000 });
            ViewUtils.fetchRepeatedlyWithDelay(this, this.options.locations, { fetchOptions: { reset: true }, doitnow: true });

            var id = $(this.$el).find("#circles-map");
            if (this.options.offline) {
                id.find("#circles-map-message").html("(map off in offline mode)");
            } else {
                requirejs(["googlemaps"], function (GoogleMaps) {
                    _.defer( function() {
                        log("loading google maps")
                        var map = GoogleMaps.addMapToCanvas(id[0],
                                // brooklyn bridge
//                              40.7063, -73.9971, 14
                                // edinburgh + atlantic
//                              55.6, -2.5, 2
                                // center
                                0, 0, 1
                                )
                        var locatedLocations = new Location.UsageLocated()
                        // googlemaps.js isn't re-loaded during tab-to-tab navigation so we need to reset it each time
                        // the maps is re-drawn to reset the cached set of location markers
                        GoogleMaps.resetCircles()
                        that.updateCircles(that, locatedLocations, GoogleMaps, map)
                        that.callPeriodically("circles", function() {
                            that.updateCircles(that, locatedLocations, GoogleMaps, map)
                        }, 10000)
                    })
                }, function (error) {
                        id.find("#circles-map-message").html("(map not available)"); 
                });
            }            
        },
        
        updateCircles: function(that, locatedLocations, GoogleMaps, map) {
            locatedLocations.fetch({success:function() {
                GoogleMaps.drawCircles(map, locatedLocations.attributes)
            }})
        },
        
        // cleaning code goes here
        beforeClose:function () {
            this.collection.off("reset", this.render)
            this.options.locations.off("reset", this.renderSummaries)
            // iterate over all (sub)views and destroy them
            _.each(this._appViews, function (value) {
                value.close()
            })
            this._appViews = null
        },

        render:function () {
            this.renderSummaries()
            this.renderCollection()
            return this
        },

        renderSummaries:function () {
            this.$('.home-summaries-row').html(this.summariesView.render().el )
        },
        
        renderCollection:function () {
            var $tableBody = this.$('#applications-table-body').empty()
            if (this.collection==null)
                $tableBody.append("<tr><td colspan='3'><i>No data available</i></td></tr>");
            else if (this.collection.isEmpty())
                $tableBody.append("<tr><td colspan='3'><i>No applications deployed</i></td></tr>");
            else this.collection.each(function (app) {
                var appView = new HomeView.AppEntryView({model:app})
                if (this._appViews[app.cid]) {
                    // if the application has a view destroy it
                    this._appViews[app.cid].close()
                }
                this._appViews[app.cid] = appView
                $tableBody.append(appView.render().el)
            }, this)
        },

        createApplication:function () {
            if (this._modal) {
                this._modal.close()
            }
            var that = this;
            if (!this.options.offline) {
                var wizard = new AppAddWizard({appRouter:this.options.appRouter})
                this._modal = wizard
                this.$(".add-app #modal-container").html(wizard.render().el)
                this.$(".add-app #modal-container .modal")
                    .on("hidden",function () {
                        wizard.close()
                        that.collection.fetch({reset:true});
                    }).modal('show')
            }
        },

        reloadBrooklynProperties: function() {
            var self = this;
            // indicate submitted
            self.$('#reload-brooklyn-properties-indicator').show();
            $.ajax({
                type: "POST",
                url: "/v1/server/properties/reload",
                contentType: "application/json",
                success: function() {
                    console.log("Reloaded brooklyn properties");
                    self.options.locations.fetch();
                    // clear submitted indicator
                    setTimeout(function() { self.$('#reload-brooklyn-properties-indicator').hide(); }, 250);
                },
                error: function(data) {
                    // TODO render the error better than poor-man's flashing
                    // (would just be connection error -- with timeout=0 we get a task even for invalid input)
                    self.$el.fadeTo(100,1).delay(200).fadeTo(200,0.2).delay(200).fadeTo(200,1);
                    self.$('#reload-brooklyn-properties-indicator').hide();
                    console.error("ERROR reloading brooklyn properties");
                    console.debug(data);
                }
            });
        }
    })

    HomeView.HomeSummariesView = Backbone.View.extend({
        tagName:'div',
        template:_.template(HomeSummariesHtml),

        initialize:function () {
//            this.apps.on('change', this.render, this)
        },
        render:function () {
            this.$el.html(this.template({
                apps:this.options.applications,
                locations:this.options.locations
            }))
            return this
        },
        beforeClose:function () {
//            this.off("change", this.render)
        }
    })
    
    HomeView.AppEntryView = Backbone.View.extend({
        tagName:'tr',

        template:_.template(AppEntryHtml),

        initialize:function () {
            this.model.on('change', this.render, this)
            this.model.on('destroy', this.close, this)
        },
        render:function () {
            this.$el.html(this.template({
                cid:this.model.cid,
                link:this.model.getLinkByName("self"),
                name:this.model.getSpec().get("name"),
                status:this.model.get("status")
            }))
            return this
        },
        beforeClose:function () {
            this.off("change", this.render)
            this.off("destroy", this.close)
        }
    })

    return HomeView
})
