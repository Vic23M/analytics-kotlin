package com.segment.analytics.destinations.plugins

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.FirebaseAnalytics.Event
import com.google.firebase.analytics.FirebaseAnalytics.Param
import com.segment.analytics.*
import com.segment.analytics.platform.DestinationPlugin
import com.segment.analytics.platform.plugins.android.AndroidLifecycle
import com.segment.analytics.platform.plugins.log
import com.segment.analytics.utilities.getDouble
import com.segment.analytics.utilities.getMapSet
import com.segment.analytics.utilities.getString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

class FirebaseDestination(
        private val context: Context
) : DestinationPlugin(), AndroidLifecycle {

    override val name: String = "Firebase"
    private lateinit var firebaseAnalytics: FirebaseAnalytics
    private var activity: Activity? = null

    companion object {
        private val EVENT_MAPPER: Map<String, String> = mapOf(
                "Product Added" to Event.ADD_TO_CART,
                "Checkout Started" to Event.BEGIN_CHECKOUT,
                "Order Completed" to Event.ECOMMERCE_PURCHASE,
                "Order Refunded" to Event.PURCHASE_REFUND,
                "Product Viewed" to Event.VIEW_ITEM,
                "Product List Viewed" to Event.VIEW_ITEM_LIST,
                "Payment Info Entered" to Event.ADD_PAYMENT_INFO,
                "Promotion Viewed" to Event.PRESENT_OFFER,
                "Product Added to Wishlist" to Event.ADD_TO_WISHLIST,
                "Product Shared" to Event.SHARE,
                "Product Clicked" to Event.SELECT_CONTENT,
                "Products Searched" to Event.SEARCH
        )

        private val PROPERTY_MAPPER: Map<String, String> = mapOf(
                "category" to Param.ITEM_CATEGORY,
                "product_id" to Param.ITEM_ID,
                "name" to Param.ITEM_NAME,
                "price" to Param.PRICE,
                "quantity" to Param.QUANTITY,
                "query" to Param.SEARCH_TERM,
                "shipping" to Param.SHIPPING,
                "tax" to Param.TAX,
                "total" to Param.VALUE,
                "revenue" to Param.VALUE,
                "order_id" to Param.TRANSACTION_ID,
                "currency" to Param.CURRENCY,
                "products" to Param.ITEM_LIST
        )

        private val PRODUCT_MAPPER: Map<String, String> = mapOf(
                "category" to Param.ITEM_CATEGORY,
                "product_id" to Param.ITEM_ID,
                "id" to Param.ITEM_ID,
                "name" to Param.ITEM_NAME,
                "price" to Param.PRICE,
                "quantity" to Param.QUANTITY
        )
    }

    override fun setup(analytics: Analytics) {
        super.setup(analytics)

        firebaseAnalytics = FirebaseAnalytics.getInstance(context)
    }

    override fun identify(payload: IdentifyEvent): BaseEvent? {
        var returnPayload = super.identify(payload)

        firebaseAnalytics.setUserId(payload.userId)

        payload.traits.let {
            for ((traitKey, traitValue) in it) {
                val updatedTrait = makeKey(traitValue.toString())
                firebaseAnalytics.setUserProperty(traitKey, updatedTrait)
            }
        }

        return returnPayload
    }

    override fun track(payload: TrackEvent): BaseEvent? {
        var returnPayload = super.track(payload)

        // Clean the eventName up
        var eventName = payload.event
        if (EVENT_MAPPER.containsKey(eventName)) {
            eventName = EVENT_MAPPER[eventName].toString()
        } else {
            eventName = makeKey(eventName)
        }

        payload.properties.let {
            val formattedProperties = formatProperties(it)
            firebaseAnalytics.logEvent(eventName, formattedProperties)
            analytics.log("firebaseAnalytics.logEvent($eventName, $formattedProperties)")
        }

        return returnPayload
    }

    override fun screen(payload: ScreenEvent): BaseEvent? {
        var returnPayload = super.screen(payload)

        val tempActivity = activity
        if (tempActivity != null) {
            firebaseAnalytics.setCurrentScreen(tempActivity, payload.name, null);
        }

        return returnPayload
    }


    // AndroidActivity Methods
    override fun onActivityResumed(activity: Activity?) {
        super.onActivityResumed(activity)

        try {
            val packageManager = activity?.packageManager ?: return

            packageManager.getActivityInfo(activity.componentName, PackageManager.GET_META_DATA).let {
                it.loadLabel(packageManager).toString().let {
                    firebaseAnalytics.setCurrentScreen(activity, it, null)
                    analytics.log("firebaseAnalytics.setCurrentScreen(activity, $it, null")
                }
            }
        } catch (exception: PackageManager.NameNotFoundException) {
            analytics.log("Activity Not Found: " + exception.toString())
        }
    }

    override fun onActivityStarted(activity: Activity?) {
        super.onActivityStarted(activity)
        this.activity = activity
    }

    override fun onActivityStopped(activity: Activity?) {
        super.onActivityStopped(activity)
        this.activity = null
    }

    // Private Helper Methods

    // Format properties into a format needed by firebase
    private fun formatProperties(properties: Properties): Bundle? {

        var bundle: Bundle? = Bundle()

        val revenue = properties.getDouble("revenue") ?: 0.0
        val total = properties.getDouble("total") ?: 0.0
        val currency = properties.getString("currency") ?: ""
        if ((revenue != 0.0 || total != 0.0) &&
            currency.isEmpty() == false) {
            bundle?.putString(Param.CURRENCY, "USD")
        }

        for ((property, value) in properties.entries) {
            var finalProperty = makeKey(property)
            if (PROPERTY_MAPPER.containsKey(property)) {
                finalProperty = PROPERTY_MAPPER.get(property).toString()
            }

            if (finalProperty.equals(FirebaseAnalytics.Param.ITEM_LIST)) {
                val products = properties.getMapSet("products") ?: continue
                val formattedProducts = formatProducts(products)
                bundle?.putParcelableArrayList(finalProperty, formattedProducts)
            } else if (bundle != null) {
                putValue(bundle, finalProperty, value)
            }
        }


        // Don't return a valid bundle if there wasn't anything added
        if (bundle?.isEmpty == true) {
            bundle = null
        }

        return bundle
    }

    private fun formatProducts(products: Set<Map<String, Any>>): ArrayList<Bundle>? {

        val mappedProducts: ArrayList<Bundle> = ArrayList()

        for (product in products) {
            val mappedProduct = Bundle()
            for (key in product.keys) {
                var value = product[key] as JsonPrimitive
                val finalKey = if (PRODUCT_MAPPER.containsKey(key)) {
                    PRODUCT_MAPPER[key] ?: makeKey(key)
                } else {
                    makeKey(key)
                }
                putValue(mappedProduct, finalKey, value.content)
            }
            mappedProducts.add(mappedProduct)
        }

        return mappedProducts
    }

    // Make sure keys do not contain ".", "-", " ", ":"
    private fun makeKey(key: String): String {
        val charsToFilter = ".- :"
        // only filter out the characters in charsToFilter by relying on the other characters not being found
        return key.filter { charsToFilter.indexOf(it) == -1 }
    }

    // Adds the appropriate value & type to a supplied bundle
    private fun putValue(bundle: Bundle, key: String, value: Any) {

        when (value) {
            is Int -> {
                bundle.putInt(key, value)
            }
            is Double -> {
                bundle.putDouble(key, value)
            }
            is Long -> {
                bundle.putLong(key, value)
            }
            else -> {
                val stringValue = value.toString()
                bundle.putString(key, stringValue)
            }
        }
    }
}