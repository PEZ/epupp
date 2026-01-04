(ns icons
  "SVG icon components for the extension UI.
   Icons sourced from Heroicons (MIT) and Lucide (ISC) - inline for zero dependencies.")

(defn pencil
  "Edit/pencil icon - Heroicons mini"
  ([] (pencil {}))
  ([{:keys [size] :or {size 16}}]
   [:svg {:xmlns "http://www.w3.org/2000/svg"
          :width size
          :height size
          :viewBox "0 0 20 20"
          :fill "currentColor"}
    [:path {:d "M17.414 2.586a2 2 0 00-2.828 0L7 10.172V13h2.828l7.586-7.586a2 2 0 000-2.828z"}]
    [:path {:fill-rule "evenodd"
            :d "M2 6a2 2 0 012-2h4a1 1 0 010 2H4v10h10v-4a1 1 0 112 0v4a2 2 0 01-2 2H4a2 2 0 01-2-2V6z"
            :clip-rule "evenodd"}]]))

(defn x
  "Close/delete X icon - Heroicons mini"
  ([] (x {}))
  ([{:keys [size] :or {size 16}}]
   [:svg {:xmlns "http://www.w3.org/2000/svg"
          :width size
          :height size
          :viewBox "0 0 20 20"
          :fill "currentColor"}
    [:path {:fill-rule "evenodd"
            :d "M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z"
            :clip-rule "evenodd"}]]))

(defn jack-in
  "Scittle Tamper logo - lightning bolt in circle"
  ([] (jack-in {}))
  ([{:keys [size] :or {size 36}}]
   [:svg {:xmlns "http://www.w3.org/2000/svg"
          :width size
          :height size
          :viewBox "0 0 100 100"}
    [:circle {:cx "50"
              :cy "50"
              :r "48"
              :stroke "#4a71c4"
              :stroke-width "4"
              :fill "#4a71c4"}]
    [:path
     {:fill "#ffdc73"
      :transform "translate(50, 50) scale(0.5) translate(-211, -280)"
      :d "M224.12 259.93h21.11a5.537 5.537 0 0 1 4.6 8.62l-50.26 85.75a5.536 5.536 0 0 1-7.58 1.88 5.537 5.537 0 0 1-2.56-5.85l7.41-52.61-24.99.43a5.538 5.538 0 0 1-5.61-5.43c0-1.06.28-2.04.78-2.89l49.43-85.71a5.518 5.518 0 0 1 7.56-1.95 5.518 5.518 0 0 1 2.65 5.53l-2.54 52.23z"}]]))
